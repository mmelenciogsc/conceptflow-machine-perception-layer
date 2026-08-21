// SPDX-License-Identifier: MIT OR Apache-2.0
using System;
using System.Collections.Generic;
using UnityEngine;

namespace ConceptFlow.Mpl.PerceptionLab
{
    public enum SpeakerBank { Left, Right, Superior, Inferior }

    public readonly struct SpeakerEmitter
    {
        public readonly string Id;
        public readonly SpeakerBank Bank;
        public readonly int Ring;
        public readonly Vector3 Direction;
        public readonly Vector3 Position;
        public readonly Vector3 InwardNormal;
        public SpeakerEmitter(string id, SpeakerBank bank, int ring, Vector3 direction, Vector3 position,Vector3 inwardNormal)
        { Id=id; Bank=bank; Ring=ring; Direction=direction; Position=position; InwardNormal=inwardNormal; }
    }

    public sealed class VirtualSpeakerField
    {
        public readonly IReadOnlyList<SpeakerEmitter> Emitters;
        private readonly float concentration;

        public VirtualSpeakerField(BodySurfaceField body, CanonicalConfig config)
        {
            concentration = config.speakerArray.angularConcentration;
            var output = new List<SpeakerEmitter>(4 * config.speakerArray.ringAnglesDegrees.Length * config.speakerArray.samplesPerRing);
            foreach (SpeakerBank bank in Enum.GetValues(typeof(SpeakerBank)))
            {
                Basis(bank, out Vector3 axis, out Vector3 a, out Vector3 b);
                for (int ring=0; ring<config.speakerArray.ringAnglesDegrees.Length; ring++)
                {
                    float angle = config.speakerArray.ringAnglesDegrees[ring] * Mathf.Deg2Rad;
                    for (int sample=0; sample<config.speakerArray.samplesPerRing; sample++)
                    {
                        float phase = 2f*Mathf.PI*sample/config.speakerArray.samplesPerRing;
                        Vector3 direction = (axis*Mathf.Cos(angle)+a*Mathf.Sin(angle)*Mathf.Cos(phase)+b*Mathf.Sin(angle)*Mathf.Sin(phase)).normalized;
                        Vector3 origin = new(0, config.bodyProfile.statureMeters*.52f, 0);
                        Vector3 position = ShellPoint(body,origin,direction,config.bodyProfile.statureMeters);
                        Vector3 inward = (body.Evaluate(position).SurfacePoint-position).normalized;
                        output.Add(new SpeakerEmitter($"{bank.ToString().ToLowerInvariant()}-r{ring:D2}-s{sample:D2}", bank, ring, direction, position, inward));
                    }
                }
            }
            Emitters = output;
        }

        private static Vector3 ShellPoint(BodySurfaceField body,Vector3 origin,Vector3 direction,float statureMeters)
        {
            float lower=0f;
            float upper=Mathf.Max(5f,body.BubbleRadiusMeters+statureMeters+1f);
            if(body.Evaluate(origin+direction*upper).Distance<body.BubbleRadiusMeters) throw new InvalidOperationException("Speaker shell search did not leave the configured body offset.");
            for(int iteration=0;iteration<32;iteration++)
            {
                float middle=(lower+upper)*.5f;
                if(body.Evaluate(origin+direction*middle).Distance<body.BubbleRadiusMeters) lower=middle;
                else upper=middle;
            }
            return origin+direction*((lower+upper)*.5f);
        }

        public float[] Weights(Vector3 intrusionDirection)
        {
            float[] weights = new float[Emitters.Count];
            Weights(intrusionDirection,weights);
            return weights;
        }

        public void Weights(Vector3 intrusionDirection,float[] weights)
        {
            if(weights==null||weights.Length!=Emitters.Count) throw new ArgumentException("Weight buffer must match emitter count.",nameof(weights));
            Vector3 direction = intrusionDirection.normalized;
            float total=0f;
            for(int i=0;i<Emitters.Count;i++) { weights[i]=Mathf.Exp(concentration*(Vector3.Dot(Emitters[i].Direction,direction)-1f)); total+=weights[i]; }
            if(total<=0f) throw new InvalidOperationException("Speaker weights have zero energy.");
            for(int i=0;i<weights.Length;i++) weights[i]/=total;
        }

        private static void Basis(SpeakerBank bank, out Vector3 axis, out Vector3 a, out Vector3 b)
        {
            switch(bank)
            {
                case SpeakerBank.Left: axis=Vector3.left; a=Vector3.up; b=Vector3.forward; break;
                case SpeakerBank.Right: axis=Vector3.right; a=Vector3.up; b=Vector3.back; break;
                case SpeakerBank.Superior: axis=Vector3.up; a=Vector3.right; b=Vector3.forward; break;
                default: axis=Vector3.down; a=Vector3.right; b=Vector3.back; break;
            }
        }
    }
}
