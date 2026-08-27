// SPDX-License-Identifier: MIT OR Apache-2.0
// Define CONCEPTFLOW_FMOD_UNITY and add the FMOD Unity assembly reference only in a
// consumer project that has licensed FMOD Unity integration installed.
#if CONCEPTFLOW_FMOD_UNITY
using System;
using System.Globalization;
using UnityEngine;

namespace ConceptFlow.Mpl.PerceptionLab
{
    public sealed class FmodStudioPerceptionAudioBackend : IPerceptionAudioBackend, IDisposable
    {
        private global::FMOD.Studio.EventInstance focusedIcon;
        private string focusedTrackId=string.Empty;
        private string focusedEventPath=string.Empty;
        private long dwellGeneration;
        private bool dwellSpeechActive;

        public string Dispatch(SpatialAudioCommand command)
        {
            global::FMOD.Studio.EventInstance instance=global::FMODUnity.RuntimeManager.CreateInstance(command.EventPath);
            Require(instance.set3DAttributes(Attributes(command.Position,command.InwardNormal,Vector3.up)),"set geometry 3D attributes");
            Require(instance.setVolume(command.Gain),"set geometry volume");
            instance.setParameterByName("SoundSize",command.SoundSizeMeters,true);
            Require(instance.start(),"start geometry event");
            Require(instance.release(),"release geometry event");
            return "fmod:start:"+command.EventPath;
        }

        public string SetListenerPose(ListenerPoseCommand command)
        {
            Require(global::FMODUnity.RuntimeManager.StudioSystem.setListenerAttributes(
                0,Attributes(command.Position,command.Forward,command.Up)),"set listener attributes");
            return "fmod:listener:"+command.TimestampNs.ToString(CultureInfo.InvariantCulture);
        }

        public string UpsertFocusedIcon(FocusedIconCommand command)
        {
            bool created=!focusedIcon.isValid() || focusedTrackId!=command.TrackId || focusedEventPath!=command.EventPath;
            if(created)
            {
                StopFocusedIcon("replace");
                focusedIcon=global::FMODUnity.RuntimeManager.CreateInstance(command.EventPath);
                if(!focusedIcon.isValid()) throw new InvalidOperationException("FMOD focused icon instance is invalid.");
            }
            focusedTrackId=command.TrackId; focusedEventPath=command.EventPath;
            Require(focusedIcon.set3DAttributes(Attributes(command.Position,command.Forward,command.Up)),"set focused icon 3D attributes");
            Require(focusedIcon.setVolume(command.Gain),"set focused icon volume");
            foreach(AudioParameter parameter in command.Parameters)
                Require(focusedIcon.setParameterByName(parameter.Name,parameter.Value,true),"set "+parameter.Name);
            Require(focusedIcon.setParameterByName("DwellSpeechActive",dwellSpeechActive?1f:0f,true),
                "set dwell speech state");
            if(created) Require(focusedIcon.start(),"start focused icon");
            return created?"fmod:focused:start":"fmod:focused:update";
        }

        public string StopFocusedIcon(string reason)
        {
            if(focusedIcon.isValid())
            {
                focusedIcon.stop(global::FMOD.Studio.STOP_MODE.ALLOWFADEOUT);
                focusedIcon.release();
                focusedIcon.clearHandle();
            }
            focusedTrackId=string.Empty; focusedEventPath=string.Empty;
            return "fmod:focused:stop";
        }

        public string SetDwellSpeech(long generation,bool active,float requestedDuckGain)
        {
            if(generation<=0L) throw new ArgumentOutOfRangeException(nameof(generation));
            if(float.IsNaN(requestedDuckGain) || float.IsInfinity(requestedDuckGain)
                || requestedDuckGain<0f || requestedDuckGain>1f)
                throw new ArgumentOutOfRangeException(nameof(requestedDuckGain));
            if(active)
            {
                if(generation<dwellGeneration) return "fmod:duck:ignored";
                dwellGeneration=generation; dwellSpeechActive=true;
            }
            else
            {
                if(generation!=dwellGeneration || !dwellSpeechActive) return "fmod:unduck:ignored";
                dwellSpeechActive=false;
            }
            if(focusedIcon.isValid())
                Require(focusedIcon.setParameterByName("DwellSpeechActive",active?1f:0f,true),"set dwell speech state");
            return active?"fmod:duck":"fmod:unduck";
        }

        public string EmitInterfaceState(InterfaceAudioCommand command)
        {
            global::FMOD.Studio.EventInstance instance=global::FMODUnity.RuntimeManager.CreateInstance(
                InspectableFmodBackend.InterfaceStateEvent);
            Require(instance.setParameterByName("InterfaceState",command.StateIndex,true),"set interface state");
            Require(instance.start(),"start interface state"); Require(instance.release(),"release interface state");
            return "fmod:interface:"+command.State;
        }

        public void Dispose() => StopFocusedIcon("dispose");

        private static global::FMOD.ATTRIBUTES_3D Attributes(Vector3 position,Vector3 forward,Vector3 up)
        {
            return new global::FMOD.ATTRIBUTES_3D
            {
                position=Vector(position), velocity=Vector(Vector3.zero), forward=Vector(forward), up=Vector(up),
            };
        }

        private static global::FMOD.VECTOR Vector(Vector3 value)
        { return new global::FMOD.VECTOR { x=value.x,y=value.y,z=value.z }; }

        private static void Require(global::FMOD.RESULT result,string operation)
        {
            if(result!=global::FMOD.RESULT.OK) throw new InvalidOperationException(operation+": "+result);
        }
    }
}
#endif
