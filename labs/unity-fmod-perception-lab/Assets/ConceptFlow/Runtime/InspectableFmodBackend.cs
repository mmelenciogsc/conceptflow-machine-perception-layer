// SPDX-License-Identifier: MIT OR Apache-2.0
using System;
using System.Globalization;
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

    public sealed class InspectableFmodBackend
    {
        public const string AnchorEvent = "event:/MachinePerception/SoundBubble/IntrusionAnchor";
        public const string FieldEvent = "event:/MachinePerception/SoundBubble/EnvelopmentField";

        public string Dispatch(SpatialAudioCommand command)
        {
            if(command.Gain<0f||command.Gain>1f||command.SoundSizeMeters<0f) throw new ArgumentOutOfRangeException(nameof(command));
            string value=string.Format(CultureInfo.InvariantCulture,"event={0};layer={1};position={2:F3},{3:F3},{4:F3};gain={5:F3};soundSizeMeters={6:F3}",command.EventPath,command.Layer,command.Position.x,command.Position.y,command.Position.z,command.Gain,command.SoundSizeMeters);
            Debug.Log("[MPL_FMOD_FALLBACK] "+value);
            return value;
        }
    }
}
