// SPDX-License-Identifier: MIT OR Apache-2.0
using System;
using System.Collections.Generic;
using UnityEngine;

namespace ConceptFlow.Mpl.PerceptionLab
{
    public enum BodyRegion { Head, ShoulderNeck, Torso, LeftLateral, RightLateral, RearTorso, Pelvis, LowerBody }

    public readonly struct BodyCapsule
    {
        public readonly BodyRegion Region;
        public readonly Vector3 Start;
        public readonly Vector3 End;
        public readonly float Radius;

        public BodyCapsule(BodyRegion region, Vector3 start, Vector3 end, float radius)
        { Region = region; Start = start; End = end; Radius = radius; }

        public Vector3 ClosestAxis(Vector3 point)
        {
            Vector3 axis = End - Start;
            float square = axis.sqrMagnitude;
            if (square <= 1e-9f) return Start;
            return Start + axis * Mathf.Clamp01(Vector3.Dot(point - Start, axis) / square);
        }
    }

    public readonly struct Clearance
    {
        public readonly Vector3 SurfacePoint;
        public readonly float Distance;
        public readonly float Proximity;
        public readonly BodyRegion Region;
        public readonly bool Inside;

        public Clearance(Vector3 surfacePoint, float distance, float proximity, BodyRegion region, bool inside)
        { SurfacePoint = surfacePoint; Distance = distance; Proximity = proximity; Region = region; Inside = inside; }
    }

    public sealed class BodySurfaceField
    {
        public const float DefaultBubbleRadiusMeters = 0.9144f;
        public readonly float BubbleRadiusMeters;
        public readonly IReadOnlyList<BodyCapsule> Segments;

        public BodySurfaceField(CanonicalConfig config)
        {
            if (config == null) throw new ArgumentNullException(nameof(config));
            BubbleRadiusMeters = config.bubbleRadiusMeters;
            BodyProfileData p = config.bodyProfile;
            float shoulderY = p.statureMeters - 0.30f;
            float hipY = p.statureMeters * 0.53f;
            float kneeY = p.statureMeters * 0.29f;
            float lateralX = p.shoulderWidthMeters * 0.5f - 0.065f;
            float rearZ = -p.torsoDepthMeters * 0.5f + 0.035f;
            Segments = new[] {
                new BodyCapsule(BodyRegion.Head, new Vector3(0,p.statureMeters-p.headRadiusMeters,0), new Vector3(0,p.statureMeters-p.headRadiusMeters,0), p.headRadiusMeters),
                new BodyCapsule(BodyRegion.ShoulderNeck, new Vector3(-p.shoulderWidthMeters*.5f,shoulderY,0), new Vector3(p.shoulderWidthMeters*.5f,shoulderY,0), .075f),
                new BodyCapsule(BodyRegion.Torso, new Vector3(0,hipY+.08f,0), new Vector3(0,shoulderY-.08f,0), p.torsoDepthMeters*.5f),
                new BodyCapsule(BodyRegion.LeftLateral, new Vector3(-lateralX,hipY+.08f,0), new Vector3(-lateralX,shoulderY-.10f,0), .075f),
                new BodyCapsule(BodyRegion.RightLateral, new Vector3(lateralX,hipY+.08f,0), new Vector3(lateralX,shoulderY-.10f,0), .075f),
                new BodyCapsule(BodyRegion.RearTorso, new Vector3(0,hipY+.08f,rearZ), new Vector3(0,shoulderY-.10f,rearZ), .075f),
                new BodyCapsule(BodyRegion.Pelvis, new Vector3(-p.hipWidthMeters*.5f,hipY,0), new Vector3(p.hipWidthMeters*.5f,hipY,0), .10f),
                new BodyCapsule(BodyRegion.LowerBody, new Vector3(-p.hipWidthMeters*.22f,.08f,0), new Vector3(-p.hipWidthMeters*.22f,kneeY,0), .075f),
                new BodyCapsule(BodyRegion.LowerBody, new Vector3(p.hipWidthMeters*.22f,.08f,0), new Vector3(p.hipWidthMeters*.22f,kneeY,0), .075f)
            };
        }

        public Clearance Evaluate(Vector3 bodyPoint)
        {
            float best = float.PositiveInfinity;
            bool bestInside = false;
            Vector3 bestSurface = default;
            BodyRegion bestRegion = default;
            for (int index = 0; index < Segments.Count; index++)
            {
                BodyCapsule segment = Segments[index];
                Vector3 axis = segment.ClosestAxis(bodyPoint);
                Vector3 delta = bodyPoint - axis;
                float centerDistance = delta.magnitude;
                bool inside = centerDistance <= segment.Radius;
                float distance = Mathf.Max(0f, centerDistance - segment.Radius);
                Vector3 direction = centerDistance <= 1e-9f ? Vector3.right : delta / centerDistance;
                if ((!bestInside && inside) || (inside == bestInside && distance < best))
                {
                    best = distance; bestInside = inside; bestSurface = axis + direction * segment.Radius; bestRegion = segment.Region;
                }
            }
            return new Clearance(bestSurface, best, Mathf.Clamp01(1f - best / BubbleRadiusMeters), bestRegion, bestInside);
        }
    }
}
