// SPDX-License-Identifier: MIT OR Apache-2.0
using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Text;
using UnityEngine;

namespace ConceptFlow.Mpl.PerceptionLab
{
    public interface IHrtfAudioProfileCapability
    {
        bool IsHrtfProfileReady(string requiredProfile);
    }

    public enum HrtfCalibrationState
    {
        Idle, ReadyForTrial, Presenting, AwaitingResponse, Completed, Aborted
    }

    [Serializable]
    public sealed class HrtfLocalizationTrial
    {
        public string trial_id=string.Empty;
        public int ordinal;
        public int block_index;
        public string direction_label=string.Empty;
        public float azimuth_deg;
        public float elevation_deg;
        public float distance_m;
        public int presentation_count;
        public string profile=string.Empty;
    }

    [Serializable]
    public sealed class HrtfLocalizationManifest
    {
        public string schema=string.Empty;
        public HrtfLocalizationTrial[] trials=Array.Empty<HrtfLocalizationTrial>();
    }

    public sealed class HrtfLocalizationCalibration
    {
        public const string ManifestSchema="conceptflow.hrtf-localization-manifest/v1";
        public const string ResponseSchema="conceptflow.hrtf-localization-response/v1";
        public const string Profile="resonance_audio";
        public const float DistanceMeters=2f;
        public const int PresentationCount=3;
        public const long MaximumPoseAgeNs=250_000_000L;
        public const long PulseDurationNs=220_000_000L;
        public const long PulseIntervalNs=500_000_000L;
        public const long MaximumTrialDurationNs=15_000_000_000L;
        private const float DefaultCalibrationGain=.70f;
        private const float Salience=1f;
        private static readonly HashSet<string> DirectionLabels=new(StringComparer.Ordinal)
        {
            "front","front_left","left","rear_left","rear","rear_right","right","front_right",
            "above_front","above","below_front","below",
        };

        private readonly IPerceptionAudioBackend backend;
        private readonly IHrtfAudioProfileCapability audioProfileCapability;
        private readonly Func<bool> isAudioRouteReady;
        private readonly HrtfLocalizationTrial[] trials;
        private readonly string resultDirectory;
        private ListenerPoseCommand listenerPose;
        private bool hasListenerPose;
        private bool pulseActive;
        private int trialIndex;
        private int pulseIndex;
        private long nextPulseTimestampNs;
        private long pulseStopTimestampNs;
        private long trialDeadlineNs;
        private string sessionId=string.Empty;
        private string resultPath=string.Empty;
        private float ambientCalibrationGain=DefaultCalibrationGain;
        private long ambientPulseIntervalNs=600_000_000L;
        private long ambientProfileValidUntilNs;

        public HrtfLocalizationCalibration(IPerceptionAudioBackend backend,string manifestJson,string resultDirectory,
            Func<bool> isAudioRouteReady=null)
        {
            this.backend=backend??throw new ArgumentNullException(nameof(backend));
            audioProfileCapability=backend as IHrtfAudioProfileCapability;
            this.isAudioRouteReady=isAudioRouteReady??HrtfAudioRouteReadiness.IsReady;
            if(string.IsNullOrWhiteSpace(manifestJson)) throw new ArgumentException("HRTF manifest is required.",nameof(manifestJson));
            if(string.IsNullOrWhiteSpace(resultDirectory)) throw new ArgumentException("Result directory is required.",nameof(resultDirectory));
            HrtfLocalizationManifest manifest=JsonUtility.FromJson<HrtfLocalizationManifest>(manifestJson);
            trials=ValidateManifest(manifest);
            this.resultDirectory=resultDirectory;
        }

        public HrtfCalibrationState State { get; private set; }=HrtfCalibrationState.Idle;
        public bool SuppressOrdinaryAudio =>
            State==HrtfCalibrationState.ReadyForTrial || State==HrtfCalibrationState.Presenting ||
            State==HrtfCalibrationState.AwaitingResponse;
        public int TrialCount => trials.Length;
        public int AnsweredCount => trialIndex;
        public int CurrentOrdinal => trialIndex<trials.Length?trials[trialIndex].ordinal:0;
        public string CurrentTrialId => trialIndex<trials.Length?trials[trialIndex].trial_id:string.Empty;
        public string SessionId => sessionId;
        public string ResultPath => resultPath;
        public string LastError { get; private set; }=string.Empty;

        public bool AcceptAmbientSoundProfile(PerceptionAmbientSoundProfileSnapshot profile,long nowNs)
        {
            if(profile==null || nowNs<0L || profile.Revision<=0L || profile.SessionGeneration<=0L ||
               profile.CaptureStartTimestampNs<0L ||
               profile.CaptureEndTimestampNs<profile.CaptureStartTimestampNs ||
               profile.ValidUntilTimestampNs<nowNs ||
               !IsFinite(profile.RecommendedCalibrationGain) ||
               profile.RecommendedCalibrationGain<0f || profile.RecommendedCalibrationGain>1f ||
               profile.RecommendedPulseIntervalMs<450 || profile.RecommendedPulseIntervalMs>900)
                return false;
            ambientCalibrationGain=profile.RecommendedCalibrationGain;
            ambientPulseIntervalNs=profile.RecommendedPulseIntervalMs*1_000_000L;
            ambientProfileValidUntilNs=profile.ValidUntilTimestampNs;
            return true;
        }

        public void AcceptListenerPose(ListenerPoseCommand pose)
        {
            hasListenerPose=pose.TimestampNs>=0L && IsFinite(pose.Position) && IsOrientation(pose.Forward,pose.Up);
            if(hasListenerPose) listenerPose=pose;
        }

        public bool Start(string requestedSessionId,long nowNs)
        {
            if(!StopPulse("hrtf-session-start")) return AbortForAudioDispatchFailure();
            if(nowNs<0L || !IsSessionId(requestedSessionId)) return Reject("invalid-session");
            if(!AudioProfileReady()) return Reject("resonance-audio-unavailable");
            string candidate=Path.Combine(resultDirectory,requestedSessionId+".responses.ndjson");
            try
            {
                Directory.CreateDirectory(resultDirectory);
                using var stream=new FileStream(candidate,FileMode.CreateNew,FileAccess.Write,FileShare.None);
                stream.Flush(true);
            }
            catch(Exception error) when(IsStorageFailure(error))
            {
                return Reject("response-storage-unavailable");
            }
            sessionId=requestedSessionId;
            resultPath=candidate;
            trialIndex=0;
            pulseIndex=0;
            trialDeadlineNs=0L;
            LastError=string.Empty;
            State=HrtfCalibrationState.ReadyForTrial;
            return true;
        }

        public bool Next(long nowNs)
        {
            if(State!=HrtfCalibrationState.ReadyForTrial || trialIndex>=trials.Length)
                return Reject("not-ready-for-trial");
            if(!AudioProfileReady()) return Reject("resonance-audio-unavailable");
            if(!AudioRouteReady()) return Reject("hrtf-audio-route-unavailable");
            if(!HasFreshPose(nowNs)) return Reject("listener-pose-unavailable-or-stale");
            if(!StopPulse("hrtf-next-trial")) return AbortForAudioDispatchFailure();
            pulseIndex=0;
            nextPulseTimestampNs=nowNs;
            pulseStopTimestampNs=0L;
            trialDeadlineNs=nowNs>long.MaxValue-MaximumTrialDurationNs
                ?long.MaxValue:nowNs+MaximumTrialDurationNs;
            LastError=string.Empty;
            State=HrtfCalibrationState.Presenting;
            return true;
        }

        public void Tick(long nowNs)
        {
            if(State!=HrtfCalibrationState.Presenting && State!=HrtfCalibrationState.AwaitingResponse) return;
            if(nowNs<0L || nowNs>=trialDeadlineNs)
            {
                Abort("trial-deadline-exceeded");
                return;
            }
            if(!AudioRouteReady())
            {
                Abort("hrtf-audio-route-lost");
                return;
            }
            if(!AudioProfileReady())
            {
                Abort("resonance-audio-lost");
                return;
            }
            if(State==HrtfCalibrationState.AwaitingResponse) return;
            if(!HasFreshPose(nowNs))
            {
                Abort("listener-pose-unavailable-or-stale");
                return;
            }
            if(pulseActive && nowNs>=pulseStopTimestampNs)
            {
                if(!StopPulse("hrtf-pulse-complete"))
                {
                    AbortForAudioDispatchFailure();
                    return;
                }
                if(pulseIndex>=PresentationCount)
                {
                    State=HrtfCalibrationState.AwaitingResponse;
                    return;
                }
            }
            if(!pulseActive && pulseIndex<PresentationCount && nowNs>=nextPulseTimestampNs)
                PresentPulse(nowNs);
        }

        public bool Respond(string perceivedDirection,long answeredAtNs)
        {
            if(State!=HrtfCalibrationState.AwaitingResponse) return Reject("not-awaiting-response");
            if(answeredAtNs>=trialDeadlineNs)
            {
                Abort("trial-deadline-exceeded");
                return false;
            }
            if(!AudioRouteReady())
            {
                Abort("hrtf-audio-route-lost");
                return false;
            }
            if(!AudioProfileReady())
            {
                Abort("resonance-audio-lost");
                return false;
            }
            if(answeredAtNs<=0L || !DirectionLabels.Contains(perceivedDirection??string.Empty))
                return Reject("invalid-response");
            HrtfLocalizationTrial trial=trials[trialIndex];
            var response=new HrtfResponseRecord
            {
                schema=ResponseSchema,
                session_id=sessionId,
                trial_id=trial.trial_id,
                perceived_direction=perceivedDirection,
                answered_at_ns=answeredAtNs,
                target_direction=trial.direction_label,
                target_azimuth_deg=trial.azimuth_deg,
                target_elevation_deg=trial.elevation_deg,
                distance_m=trial.distance_m,
                presentation_count=trial.presentation_count,
                profile=trial.profile,
            };
            try
            {
                byte[] line=new UTF8Encoding(false).GetBytes(JsonUtility.ToJson(response)+"\n");
                using var stream=new FileStream(resultPath,FileMode.Append,FileAccess.Write,FileShare.Read);
                stream.Write(line,0,line.Length);
                stream.Flush(true);
            }
            catch(Exception error) when(IsStorageFailure(error))
            {
                Abort("response-write-failed");
                return false;
            }
            trialIndex++;
            trialDeadlineNs=0L;
            LastError=string.Empty;
            State=trialIndex==trials.Length?HrtfCalibrationState.Completed:HrtfCalibrationState.ReadyForTrial;
            return true;
        }

        public void Abort(string reason="operator-abort")
        {
            bool stopped=StopPulse(reason);
            string normalized=string.IsNullOrWhiteSpace(reason)?"operator-abort":reason;
            LastError=stopped && normalized=="operator-abort"?string.Empty:
                stopped?normalized:"audio-dispatch-failed";
            State=HrtfCalibrationState.Aborted;
        }

        private void PresentPulse(long nowNs)
        {
            HrtfLocalizationTrial trial=trials[trialIndex];
            float azimuth=trial.azimuth_deg*Mathf.Deg2Rad;
            float elevation=trial.elevation_deg*Mathf.Deg2Rad;
            Vector3 localDirection=new(
                Mathf.Sin(azimuth)*Mathf.Cos(elevation),
                Mathf.Sin(elevation),
                Mathf.Cos(azimuth)*Mathf.Cos(elevation));
            Quaternion rotation=Quaternion.LookRotation(listenerPose.Forward,listenerPose.Up);
            Vector3 position=listenerPose.Position+rotation*localDirection*DistanceMeters;
            AudioOrientation.Orthonormalize(listenerPose.Position-position,listenerPose.Up,
                out Vector3 sourceForward,out Vector3 sourceUp);
            pulseIndex++;
            string trackId=string.Format(CultureInfo.InvariantCulture,"hrtf:{0}:{1}:p{2}",sessionId,trial.trial_id,pulseIndex);
            AudioParameter[] parameters=
            {
                new("IconConcept",0f),
                new("IconSalience",Salience),
                new("IconConfidence",1f),
                new("DistanceMeters",DistanceMeters),
                new("BeaconMode",0f),
            };
            try
            {
                float calibrationGain=nowNs<=ambientProfileValidUntilNs
                    ?ambientCalibrationGain:DefaultCalibrationGain;
                long pulseInterval=nowNs<=ambientProfileValidUntilNs
                    ?ambientPulseIntervalNs:PulseIntervalNs;
                backend.SetListenerPose(listenerPose);
                backend.UpsertFocusedIcon(new FocusedIconCommand(
                    trackId,AuditoryIconRegistry.FocusedObjectEvent,"procedural/neutral_presence",
                    position,sourceForward,sourceUp,calibrationGain,nowNs,nowNs+PulseDurationNs,parameters));
                nextPulseTimestampNs=nowNs+pulseInterval;
            }
            catch(Exception)
            {
                AbortForAudioDispatchFailure();
                return;
            }
            pulseActive=true;
            pulseStopTimestampNs=nowNs+PulseDurationNs;
        }

        private bool StopPulse(string reason)
        {
            if(pulseActive)
            {
                try { backend.StopFocusedIcon(reason); }
                catch(Exception) { pulseActive=false; return false; }
            }
            pulseActive=false;
            return true;
        }

        private bool AbortForAudioDispatchFailure()
        {
            try { backend.StopFocusedIcon("hrtf-audio-dispatch-failed"); }
            catch(Exception) { }
            pulseActive=false;
            LastError="audio-dispatch-failed";
            State=HrtfCalibrationState.Aborted;
            return false;
        }

        private bool AudioRouteReady()
        {
            try { return isAudioRouteReady(); }
            catch(Exception) { return false; }
        }

        private bool AudioProfileReady()
        {
            try { return audioProfileCapability?.IsHrtfProfileReady(Profile)==true; }
            catch(Exception) { return false; }
        }

        private static bool IsStorageFailure(Exception error) => error is IOException ||
            error is UnauthorizedAccessException || error is System.Security.SecurityException ||
            error is NotSupportedException || error is ArgumentException;

        private bool HasFreshPose(long nowNs) => hasListenerPose && nowNs>=listenerPose.TimestampNs &&
            nowNs-listenerPose.TimestampNs<=MaximumPoseAgeNs;

        private bool Reject(string reason)
        {
            LastError=reason;
            return false;
        }

        private static HrtfLocalizationTrial[] ValidateManifest(HrtfLocalizationManifest manifest)
        {
            if(manifest==null || manifest.schema!=ManifestSchema || manifest.trials==null || manifest.trials.Length!=24)
                throw new ArgumentException("Focused HRTF manifest must contain exactly 24 version-1 trials.");
            var ids=new HashSet<string>(StringComparer.Ordinal);
            var perBlock=new Dictionary<int,HashSet<string>>
            {
                [1]=new HashSet<string>(StringComparer.Ordinal),
                [2]=new HashSet<string>(StringComparer.Ordinal),
            };
            string previous=string.Empty;
            for(int index=0;index<manifest.trials.Length;index++)
            {
                HrtfLocalizationTrial trial=manifest.trials[index];
                if(trial==null || trial.ordinal!=index+1 || (trial.block_index!=1 && trial.block_index!=2) ||
                   !ids.Add(trial.trial_id??string.Empty) || !DirectionLabels.Contains(trial.direction_label??string.Empty) ||
                   previous==trial.direction_label || !IsFinite(trial.azimuth_deg) || !IsFinite(trial.elevation_deg) ||
                   trial.azimuth_deg< -180f || trial.azimuth_deg>180f ||
                   trial.elevation_deg< -90f || trial.elevation_deg>90f ||
                   Mathf.Abs(trial.distance_m-DistanceMeters)>.0001f || trial.presentation_count!=PresentationCount ||
                   trial.profile!=Profile)
                    throw new ArgumentException("Focused HRTF manifest contains an invalid trial.");
                perBlock[trial.block_index].Add(trial.direction_label);
                previous=trial.direction_label;
            }
            if(perBlock[1].Count!=12 || perBlock[2].Count!=12)
                throw new ArgumentException("Each focused HRTF block must contain all 12 directions once.");
            return manifest.trials;
        }

        private static bool IsSessionId(string value)
        {
            if(string.IsNullOrEmpty(value) || value.Length>64 || !char.IsLetterOrDigit(value[0])) return false;
            for(int index=1;index<value.Length;index++)
            {
                char item=value[index];
                if(!char.IsLetterOrDigit(item) && item!='.' && item!='_' && item!='-') return false;
            }
            return true;
        }

        private static bool IsFinite(float value) => !float.IsNaN(value)&&!float.IsInfinity(value);
        private static bool IsFinite(Vector3 value) => IsFinite(value.x)&&IsFinite(value.y)&&IsFinite(value.z);
        private static bool IsOrientation(Vector3 forward,Vector3 up) => IsFinite(forward)&&IsFinite(up)&&
            Mathf.Abs(forward.sqrMagnitude-1f)<=.01f && Mathf.Abs(up.sqrMagnitude-1f)<=.01f &&
            Mathf.Abs(Vector3.Dot(forward,up))<=.01f;

        [Serializable]
        private sealed class HrtfResponseRecord
        {
            public string schema=string.Empty;
            public string session_id=string.Empty;
            public string trial_id=string.Empty;
            public string perceived_direction=string.Empty;
            public long answered_at_ns;
            public string target_direction=string.Empty;
            public float target_azimuth_deg;
            public float target_elevation_deg;
            public float distance_m;
            public int presentation_count;
            public string profile=string.Empty;
        }
    }

    public static class HrtfAudioRouteReadiness
    {
        private const int MinimumActiveRouteApi=33;

        public static bool HasCompatibleActiveRoute(int sdkVersion,IReadOnlyList<int> activeDeviceTypes)
        {
            if(sdkVersion<MinimumActiveRouteApi || activeDeviceTypes==null) return false;
            for(int index=0;index<activeDeviceTypes.Count;index++)
            {
                int type=activeDeviceTypes[index];
                if(type==3 || type==4 || type==8 || type==22 || type==26) return true;
            }
            return false;
        }

        public static bool IsReady()
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            try
            {
                using var version=new AndroidJavaClass("android.os.Build$VERSION");
                int sdkVersion=version.GetStatic<int>("SDK_INT");
                if(sdkVersion<MinimumActiveRouteApi) return false;
                using var unityPlayer=new AndroidJavaClass("com.unity3d.player.UnityPlayer");
                using AndroidJavaObject activity=unityPlayer.GetStatic<AndroidJavaObject>("currentActivity");
                using var routeProbe=new AndroidJavaClass(
                    "org.conceptflow.mpl.host.realtime.HrtfAudioRouteProbe");
                int[] activeTypes=routeProbe.CallStatic<int[]>("activeGameRouteDeviceTypes",activity);
                return HasCompatibleActiveRoute(sdkVersion,activeTypes);
            }
            catch(Exception) { return false; }
#else
            return true;
#endif
        }
    }
}
