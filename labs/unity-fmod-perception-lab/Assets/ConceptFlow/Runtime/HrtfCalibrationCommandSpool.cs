// SPDX-License-Identifier: MIT OR Apache-2.0
using System;
using System.Globalization;
using System.IO;
using System.Text;
using UnityEngine;

namespace ConceptFlow.Mpl.PerceptionLab
{
    public sealed class HrtfCalibrationCommandSpool
    {
        public const string CommandFileName="command.next";
        public const string StatusFileName="status.json";
        private readonly string directory;
        private readonly string commandPath;
        private readonly string processingPath;
        private readonly string noncePath;
        private readonly string statusPath;
        private long lastNonce;
        private string commandError=string.Empty;
        private string lastStatusFingerprint=string.Empty;
        private bool operational;

        public HrtfCalibrationCommandSpool(string directory)
        {
            if(string.IsNullOrWhiteSpace(directory)) throw new ArgumentException("Command directory is required.",nameof(directory));
            this.directory=directory;
            commandPath=Path.Combine(directory,CommandFileName);
            processingPath=Path.Combine(directory,"command.processing");
            noncePath=Path.Combine(directory,"last_nonce");
            statusPath=Path.Combine(directory,StatusFileName);
            if(!Supported) return;
            try
            {
                Directory.CreateDirectory(directory);
                if(File.Exists(processingPath)) File.Delete(processingPath);
                if(File.Exists(noncePath) && !long.TryParse(File.ReadAllText(noncePath,Encoding.UTF8).Trim(),
                       NumberStyles.None,CultureInfo.InvariantCulture,out lastNonce))
                    throw new InvalidDataException("HRTF command nonce file is invalid.");
                operational=true;
            }
            catch(Exception)
            {
                commandError="spool-initialization-failed";
                Debug.LogWarning("[MPL_HRTF] status=disabled reason=spool-initialization-failed");
            }
        }

        public static bool Supported
        {
            get
            {
#if UNITY_EDITOR || DEVELOPMENT_BUILD
                return true;
#else
                return false;
#endif
            }
        }

        public string DirectoryPath => directory;
        public string StatusPath => statusPath;
        public long LastNonce => lastNonce;
        public bool IsOperational => operational;
        public string LastError => commandError;

        public bool ProcessOnce(HrtfLocalizationCalibration calibration,long nowNs,
            Action beforeDispatch=null)
        {
            if(calibration==null) throw new ArgumentNullException(nameof(calibration));
            try
            {
                if(!Supported || !operational || !File.Exists(commandPath)) return false;
                if(File.Exists(processingPath)) File.Delete(processingPath);
                File.Move(commandPath,processingPath);
                string raw=File.ReadAllText(processingPath,Encoding.UTF8);
                string[] fields=raw.TrimEnd('\n').Split('\t');
                if(raw.IndexOf('\r')>=0 || raw.Length==0 || raw!=raw.TrimEnd('\n')+"\n" ||
                   fields.Length<3 || fields[0]!="v1" ||
                   !long.TryParse(fields[1],NumberStyles.None,CultureInfo.InvariantCulture,out long nonce) ||
                   nonce<=lastNonce)
                {
                    commandError="malformed-or-replayed-command";
                    RefreshStatus(calibration,true);
                    return true;
                }
                lastNonce=nonce;
                AtomicWrite(noncePath,nonce.ToString(CultureInfo.InvariantCulture)+"\n");
                bool accepted;
                switch(fields[2])
                {
                    case "start" when fields.Length==4:
                        beforeDispatch?.Invoke();
                        accepted=calibration.Start(fields[3],nowNs);
                        break;
                    case "next" when fields.Length==3:
                        beforeDispatch?.Invoke();
                        accepted=calibration.Next(nowNs);
                        break;
                    case "respond" when fields.Length==4:
                        beforeDispatch?.Invoke();
                        accepted=calibration.Respond(fields[3],nowNs);
                        break;
                    case "abort" when fields.Length==3:
                        beforeDispatch?.Invoke();
                        calibration.Abort(); accepted=true;
                        break;
                    default:
                        accepted=false;
                        break;
                }
                commandError=accepted?string.Empty:
                    string.IsNullOrEmpty(calibration.LastError)?"unsupported-command":calibration.LastError;
                RefreshStatus(calibration,true);
                return true;
            }
            catch(Exception)
            {
                Disable(calibration,"spool-io-failed");
                return false;
            }
            finally
            {
                try { if(File.Exists(processingPath)) File.Delete(processingPath); }
                catch(Exception) { }
            }
        }

        public void RefreshStatus(HrtfLocalizationCalibration calibration,bool force=false)
        {
            if(!Supported || !operational || calibration==null) return;
            try
            {
                string error=string.IsNullOrEmpty(commandError)?calibration.LastError:commandError;
                string fingerprint=string.Format(CultureInfo.InvariantCulture,"{0}|{1}|{2}|{3}|{4}|{5}",
                    calibration.State,calibration.SessionId,calibration.CurrentTrialId,
                    calibration.AnsweredCount,lastNonce,error);
                if(!force && fingerprint==lastStatusFingerprint) return;
                var status=new HrtfCommandStatus
                {
                    schema="conceptflow.hrtf-command-status/v1",
                    state=calibration.State.ToString(),
                    session_id=calibration.SessionId,
                    current_trial_id=calibration.CurrentTrialId,
                    current_ordinal=calibration.CurrentOrdinal,
                    answered=calibration.AnsweredCount,
                    total=calibration.TrialCount,
                    last_nonce=lastNonce,
                    result_file=string.IsNullOrEmpty(calibration.ResultPath)?string.Empty:Path.GetFileName(calibration.ResultPath),
                    error=error,
                };
                AtomicWrite(statusPath,JsonUtility.ToJson(status)+"\n");
                lastStatusFingerprint=fingerprint;
            }
            catch(Exception)
            {
                Disable(calibration,"spool-io-failed");
            }
        }

        private void Disable(HrtfLocalizationCalibration calibration,string reason)
        {
            if(!operational) return;
            operational=false;
            commandError=reason;
            calibration?.Abort(reason);
            Debug.LogWarning("[MPL_HRTF] status=disabled reason="+reason);
        }

        private static void AtomicWrite(string destination,string value)
        {
            string temporary=destination+".tmp";
            byte[] bytes=new UTF8Encoding(false).GetBytes(value);
            using(var stream=new FileStream(temporary,FileMode.Create,FileAccess.Write,FileShare.None))
            {
                stream.Write(bytes,0,bytes.Length);
                stream.Flush(true);
            }
            if(File.Exists(destination))
            {
                try { File.Replace(temporary,destination,null); }
                catch(PlatformNotSupportedException) { File.Delete(destination); File.Move(temporary,destination); }
            }
            else File.Move(temporary,destination);
        }

        [Serializable]
        private sealed class HrtfCommandStatus
        {
            public string schema=string.Empty;
            public string state=string.Empty;
            public string session_id=string.Empty;
            public string current_trial_id=string.Empty;
            public int current_ordinal;
            public int answered;
            public int total;
            public long last_nonce;
            public string result_file=string.Empty;
            public string error=string.Empty;
        }
    }

    public static class HrtfCalibrationStorage
    {
        private const string DirectoryName="hrtf-calibration";

        public static string ResolveDirectory(string persistentDataPath)
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            return ResolveForPlatform(persistentDataPath,true,AndroidFilesDirectory);
#else
            return ResolveForPlatform(persistentDataPath,false,null);
#endif
        }

        public static string ResolveForPlatform(string persistentDataPath,bool androidPlayer,
            Func<string> androidFilesDirectory)
        {
            string root;
            if(androidPlayer)
            {
                if(androidFilesDirectory==null)
                    throw new InvalidOperationException("Android app-private files directory resolver is required.");
                root=androidFilesDirectory();
            }
            else root=persistentDataPath;
            if(string.IsNullOrWhiteSpace(root) || !Path.IsPathRooted(root))
                throw new InvalidOperationException("HRTF calibration storage root must be an absolute path.");
            return Path.Combine(root,DirectoryName);
        }

#if UNITY_ANDROID && !UNITY_EDITOR
        private static string AndroidFilesDirectory()
        {
            try
            {
                using var unityPlayer=new AndroidJavaClass("com.unity3d.player.UnityPlayer");
                using AndroidJavaObject activity=unityPlayer.GetStatic<AndroidJavaObject>("currentActivity");
                using AndroidJavaObject filesDirectory=activity.Call<AndroidJavaObject>("getFilesDir");
                return filesDirectory.Call<string>("getAbsolutePath");
            }
            catch(Exception error)
            {
                throw new InvalidOperationException("Android app-private files directory is unavailable.",error);
            }
        }
#endif
    }
}
