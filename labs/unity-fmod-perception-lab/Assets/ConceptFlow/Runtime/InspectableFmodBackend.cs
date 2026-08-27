// SPDX-License-Identifier: MIT OR Apache-2.0
using System;
using System.Collections.Generic;
using System.Globalization;
using System.Text;
using UnityEngine;

namespace ConceptFlow.Mpl.PerceptionLab
{
    public readonly struct SpatialAudioCommand
    {
        public readonly string EventPath;
        public readonly string Layer;
        public readonly Vector3 Position;
        public readonly Vector3 InwardNormal;
        public readonly float Gain;
        public readonly float SoundSizeMeters;
        public SpatialAudioCommand(string eventPath,string layer,Vector3 position,Vector3 inwardNormal,float gain,float soundSizeMeters)
        { EventPath=eventPath; Layer=layer; Position=position; InwardNormal=inwardNormal; Gain=gain; SoundSizeMeters=soundSizeMeters; }
    }

    public readonly struct AudioParameter
    {
        public readonly string Name;
        public readonly float Value;
        public AudioParameter(string name,float value) { Name=name; Value=value; }
    }

    public readonly struct ListenerPoseCommand
    {
        public readonly Vector3 Position;
        public readonly Vector3 Forward;
        public readonly Vector3 Up;
        public readonly long TimestampNs;
        public ListenerPoseCommand(Vector3 position,Vector3 forward,Vector3 up,long timestampNs)
        { Position=position; Forward=forward; Up=up; TimestampNs=timestampNs; }
    }

    public readonly struct FocusedIconCommand
    {
        public readonly string TrackId;
        public readonly string EventPath;
        public readonly string AssetKey;
        public readonly Vector3 Position;
        public readonly Vector3 Forward;
        public readonly Vector3 Up;
        public readonly float Gain;
        public readonly long SourceTimestampNs;
        public readonly long ExpiryTimestampNs;
        public readonly IReadOnlyList<AudioParameter> Parameters;
        public FocusedIconCommand(string trackId,string eventPath,string assetKey,Vector3 position,Vector3 forward,Vector3 up,
            float gain,long sourceTimestampNs,long expiryTimestampNs,IReadOnlyList<AudioParameter> parameters)
        {
            TrackId=trackId; EventPath=eventPath; AssetKey=assetKey; Position=position; Forward=forward; Up=up; Gain=gain;
            SourceTimestampNs=sourceTimestampNs; ExpiryTimestampNs=expiryTimestampNs;
            Parameters=parameters??Array.Empty<AudioParameter>();
        }
    }

    public readonly struct InterfaceAudioCommand
    {
        public readonly string State;
        public readonly int StateIndex;
        public InterfaceAudioCommand(string state,int stateIndex) { State=state; StateIndex=stateIndex; }
    }

    public interface IPerceptionAudioBackend
    {
        string Dispatch(SpatialAudioCommand command);
        string SetListenerPose(ListenerPoseCommand command);
        string UpsertFocusedIcon(FocusedIconCommand command);
        string StopFocusedIcon(string reason);
        string SetDwellSpeech(long generation,bool active,float duckGain);
        string EmitInterfaceState(InterfaceAudioCommand command);
    }

    public sealed class InspectableFmodBackend : IPerceptionAudioBackend
    {
        public const string AnchorEvent = "event:/MachinePerception/SoundBubble/IntrusionAnchor";
        public const string FieldEvent = "event:/MachinePerception/SoundBubble/EnvelopmentField";
        public const string FocusedObjectEvent = "event:/MachinePerception/AuditoryIcons/FocusedObject";
        public const string InterfaceStateEvent = "event:/MachinePerception/Interface/State";
        private string activeIconTrackId=string.Empty;
        private string activeIconEventPath=string.Empty;
        private long dwellGeneration;

        public int ActiveFocusedIconCount => string.IsNullOrEmpty(activeIconTrackId)?0:1;
        public string ActiveFocusedTrackId => activeIconTrackId;
        public int FocusedCommandCount { get; private set; }
        public int InterfaceCommandCount { get; private set; }
        public FocusedIconCommand? LastFocusedIconCommand { get; private set; }
        public ListenerPoseCommand? LastListenerPoseCommand { get; private set; }
        public bool LastFocusedDwellSpeechActive { get; private set; }
        public bool DwellSpeechActive { get; private set; }
        public float CurrentDuckGain { get; private set; } = 1f;

        public string Dispatch(SpatialAudioCommand command)
        {
            if(command.Gain<0f||command.Gain>1f||command.SoundSizeMeters<0f) throw new ArgumentOutOfRangeException(nameof(command));
            RequireFinite(command.Position,command.InwardNormal);
            string value=string.Format(CultureInfo.InvariantCulture,"event={0};layer={1};position={2:F3},{3:F3},{4:F3};forward={5:F3},{6:F3},{7:F3};gain={8:F3};soundSizeMeters={9:F3}",command.EventPath,command.Layer,command.Position.x,command.Position.y,command.Position.z,command.InwardNormal.x,command.InwardNormal.y,command.InwardNormal.z,command.Gain,command.SoundSizeMeters);
            Debug.Log("[MPL_FMOD_FALLBACK] "+value);
            return value;
        }

        public string SetListenerPose(ListenerPoseCommand command)
        {
            RequireOrthonormal(command.Forward,command.Up); RequireFinite(command.Position);
            LastListenerPoseCommand=command;
            string value=string.Format(CultureInfo.InvariantCulture,
                "action=listener;position={0:F3},{1:F3},{2:F3};forward={3:F3},{4:F3},{5:F3};up={6:F3},{7:F3},{8:F3};timestampNs={9}",
                command.Position.x,command.Position.y,command.Position.z,command.Forward.x,command.Forward.y,
                command.Forward.z,command.Up.x,command.Up.y,command.Up.z,command.TimestampNs);
            return Log(value);
        }

        public string UpsertFocusedIcon(FocusedIconCommand command)
        {
            if(string.IsNullOrWhiteSpace(command.TrackId)||string.IsNullOrWhiteSpace(command.EventPath)||string.IsNullOrWhiteSpace(command.AssetKey)||
               command.Gain<0f||command.Gain>1f||command.SourceTimestampNs<0||
               command.ExpiryTimestampNs<=command.SourceTimestampNs) throw new ArgumentException("Invalid focused icon command.");
            RequireFinite(command.Position); RequireOrthonormal(command.Forward,command.Up);
            string action=string.IsNullOrEmpty(activeIconTrackId)?"start":
                activeIconTrackId==command.TrackId&&activeIconEventPath==command.EventPath?"update":"replace";
            activeIconTrackId=command.TrackId; activeIconEventPath=command.EventPath;
            FocusedCommandCount++; LastFocusedIconCommand=command;
            LastFocusedDwellSpeechActive=DwellSpeechActive;
            var value=new StringBuilder(string.Format(CultureInfo.InvariantCulture,
                "action={0};event={1};track={2};asset={3};position={4:F3},{5:F3},{6:F3};forward={7:F3},{8:F3},{9:F3};up={10:F3},{11:F3},{12:F3};gain={13:F3};sourceTimestampNs={14};expiryTimestampNs={15}",
                action,command.EventPath,command.TrackId,command.AssetKey,command.Position.x,command.Position.y,command.Position.z,
                command.Forward.x,command.Forward.y,command.Forward.z,command.Up.x,command.Up.y,command.Up.z,
                command.Gain,command.SourceTimestampNs,command.ExpiryTimestampNs));
            foreach(AudioParameter parameter in command.Parameters)
            {
                if(string.IsNullOrWhiteSpace(parameter.Name)||!IsFinite(parameter.Value)) throw new ArgumentException("Invalid audio parameter.");
                value.AppendFormat(CultureInfo.InvariantCulture,";parameter.{0}={1:F3}",parameter.Name,parameter.Value);
            }
            return Log(value.ToString());
        }

        public string StopFocusedIcon(string reason)
        {
            string previous=activeIconTrackId; activeIconTrackId=string.Empty; activeIconEventPath=string.Empty;
            return Log($"action=stop;track={previous};reason={Sanitize(reason)}");
        }

        public string SetDwellSpeech(long generation,bool active,float duckGain)
        {
            if(generation<=0) throw new ArgumentOutOfRangeException(nameof(generation));
            if(!IsFinite(duckGain)||duckGain<0f||duckGain>1f)
                throw new ArgumentOutOfRangeException(nameof(duckGain));
            if(active)
            {
                if(generation<dwellGeneration) return Log($"action=duck_ignored;generation={generation}");
                dwellGeneration=generation; DwellSpeechActive=true; CurrentDuckGain=duckGain;
                return Log(string.Format(CultureInfo.InvariantCulture,"action=duck;generation={0};gain={1:F3}",generation,duckGain));
            }
            if(generation!=dwellGeneration||!DwellSpeechActive)
                return Log($"action=unduck_ignored;generation={generation}");
            DwellSpeechActive=false; CurrentDuckGain=1f;
            return Log($"action=unduck;generation={generation};gain=1.000");
        }

        public string EmitInterfaceState(InterfaceAudioCommand command)
        {
            if(string.IsNullOrWhiteSpace(command.State)||command.StateIndex<0) throw new ArgumentException("Invalid interface state.");
            InterfaceCommandCount++;
            return Log($"action=interface;event={InterfaceStateEvent};state={Sanitize(command.State)};parameter.InterfaceState={command.StateIndex}");
        }

        private static string Log(string value) { Debug.Log("[MPL_FMOD_FALLBACK] "+value); return value; }
        private static string Sanitize(string value) => (value??string.Empty).Replace(';','_').Replace('\n',' ').Replace('\r',' ');
        private static bool IsFinite(float value) => !float.IsNaN(value)&&!float.IsInfinity(value);
        private static void RequireFinite(params Vector3[] values)
        {
            foreach(Vector3 value in values) if(!IsFinite(value.x)||!IsFinite(value.y)||!IsFinite(value.z))
                throw new ArgumentException("Audio vectors must be finite.");
        }
        private static void RequireOrthonormal(Vector3 forward,Vector3 up)
        {
            RequireFinite(forward,up);
            if(Mathf.Abs(forward.sqrMagnitude-1f)>.01f||Mathf.Abs(up.sqrMagnitude-1f)>.01f||
               Mathf.Abs(Vector3.Dot(forward,up))>.01f) throw new ArgumentException("Audio orientation must be orthonormal.");
        }
    }
}
