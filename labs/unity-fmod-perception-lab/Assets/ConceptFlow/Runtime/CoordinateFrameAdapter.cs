// SPDX-License-Identifier: MIT OR Apache-2.0
using System;
using UnityEngine;

namespace ConceptFlow.Mpl.PerceptionLab
{
    /** Versioned boundary for an externally verified Android/canonical-to-Unity mapping. */
    public interface IPerceptionCoordinateFrameAdapterV1
    {
        string MappingId { get; }
        bool TryMapPosition(PerceptionFrame frame,Vector3 source,out Vector3 unity);
        bool TryMapHeadOrientation(PerceptionHeadPoseSnapshot pose,out Quaternion unity);
    }

    /** Safe default while the physical Android-to-Unity basis remains unverified. */
    public sealed class UnverifiedCoordinateFrameAdapterV1 : IPerceptionCoordinateFrameAdapterV1
    {
        public static readonly UnverifiedCoordinateFrameAdapterV1 Instance=new();
        private UnverifiedCoordinateFrameAdapterV1() { }
        public string MappingId => "unverified/fail-closed/v1";
        public bool TryMapPosition(PerceptionFrame frame,Vector3 source,out Vector3 unity)
        { unity=default; return false; }
        public bool TryMapHeadOrientation(PerceptionHeadPoseSnapshot pose,out Quaternion unity)
        { unity=default; return false; }
    }

    /**
     * The protocol's canonical coordinates are right-handed (+X right, +Y up,
     * +Z forward). Unity uses the same semantic axes with opposite handedness,
     * so the one required basis change is a Z reflection. This maps protocol
     * coordinates only; it does not claim camera/anatomical calibration or a
     * translated world-tracking origin.
     */
    public sealed class CanonicalProtocolCoordinateFrameAdapterV1 : IPerceptionCoordinateFrameAdapterV1
    {
        public static readonly CanonicalProtocolCoordinateFrameAdapterV1 Instance=new();
        private readonly ExplicitBasisCoordinateFrameAdapterV1 implementation;

        private CanonicalProtocolCoordinateFrameAdapterV1()
        {
            Matrix4x4 canonicalToUnity=Matrix4x4.Scale(new Vector3(1f,1f,-1f));
            implementation=new ExplicitBasisCoordinateFrameAdapterV1(
                "conceptflow-canonical-rh-to-unity-lh/z-reflection/v1",
                canonicalToUnity,
                canonicalToUnity);
        }

        public string MappingId => implementation.MappingId;
        public bool TryMapPosition(PerceptionFrame frame,Vector3 source,out Vector3 unity) =>
            implementation.TryMapPosition(frame,source,out unity);
        public bool TryMapHeadOrientation(PerceptionHeadPoseSnapshot pose,out Quaternion unity) =>
            implementation.TryMapHeadOrientation(pose,out unity);
    }

    /**
     * Explicit affine basis mapping. Construction requires a named, orthonormal,
     * handedness-changing HEAD and WORLD basis; callers own the measurement evidence.
     */
    public sealed class ExplicitBasisCoordinateFrameAdapterV1 : IPerceptionCoordinateFrameAdapterV1
    {
        private readonly Matrix4x4 unityFromHead;
        private readonly Matrix4x4 unityFromWorld;
        private readonly Matrix4x4 headFromUnity;

        public ExplicitBasisCoordinateFrameAdapterV1(
            string mappingId,Matrix4x4 unityFromHead,Matrix4x4 unityFromWorld)
        {
            if(string.IsNullOrWhiteSpace(mappingId)) throw new ArgumentException("Mapping evidence ID is required.",nameof(mappingId));
            RequireBasis(unityFromHead,nameof(unityFromHead));
            RequireBasis(unityFromWorld,nameof(unityFromWorld));
            MappingId=mappingId; this.unityFromHead=unityFromHead; this.unityFromWorld=unityFromWorld;
            headFromUnity=unityFromHead.inverse;
        }

        public string MappingId { get; }

        public bool TryMapPosition(PerceptionFrame frame,Vector3 source,out Vector3 unity)
        {
            unity=default;
            if(!IsFinite(source)) return false;
            if(frame==PerceptionFrame.Head) unity=unityFromHead.MultiplyVector(source);
            else if(frame==PerceptionFrame.World) unity=unityFromWorld.MultiplyPoint3x4(source);
            else return false;
            return IsFinite(unity);
        }

        public bool TryMapHeadOrientation(PerceptionHeadPoseSnapshot pose,out Quaternion unity)
        {
            unity=Quaternion.identity;
            if(pose==null || !IsFinite(pose.W) || !IsFinite(pose.X) || !IsFinite(pose.Y) || !IsFinite(pose.Z)) return false;
            var sourceRotation=new Quaternion(pose.X,pose.Y,pose.Z,pose.W);
            float norm=sourceRotation.x*sourceRotation.x+sourceRotation.y*sourceRotation.y+
                sourceRotation.z*sourceRotation.z+sourceRotation.w*sourceRotation.w;
            if(!IsFinite(norm)||Mathf.Abs(norm-1f)>.04f) return false;
            sourceRotation.Normalize();
            Vector3 sourceForward=headFromUnity.MultiplyVector(Vector3.forward);
            Vector3 sourceUp=headFromUnity.MultiplyVector(Vector3.up);
            Vector3 mappedForward=unityFromWorld.MultiplyVector(sourceRotation*sourceForward).normalized;
            Vector3 mappedUp=unityFromWorld.MultiplyVector(sourceRotation*sourceUp).normalized;
            if(!IsOrientation(mappedForward,mappedUp)) return false;
            unity=Quaternion.LookRotation(mappedForward,mappedUp);
            return IsFinite(unity.x)&&IsFinite(unity.y)&&IsFinite(unity.z)&&IsFinite(unity.w);
        }

        private static void RequireBasis(Matrix4x4 value,string parameterName)
        {
            for(int row=0;row<4;row++) for(int column=0;column<4;column++)
                if(!IsFinite(value[row,column])) throw new ArgumentException("Basis must be finite.",parameterName);
            if(Mathf.Abs(value[3,0])>.0001f||Mathf.Abs(value[3,1])>.0001f||
               Mathf.Abs(value[3,2])>.0001f||Mathf.Abs(value[3,3]-1f)>.0001f)
                throw new ArgumentException("Basis must be affine.",parameterName);
            Vector3 right=value.MultiplyVector(Vector3.right), up=value.MultiplyVector(Vector3.up), forward=value.MultiplyVector(Vector3.forward);
            if(!IsOrientation(right,up)||!IsOrientation(right,forward)||!IsOrientation(up,forward)||value.determinant>=-.99f||value.determinant<=-1.01f)
                throw new ArgumentException("Basis must be finite, orthonormal, and handedness-changing.",parameterName);
        }

        private static bool IsOrientation(Vector3 first,Vector3 second) => IsFinite(first)&&IsFinite(second)&&
            Mathf.Abs(first.sqrMagnitude-1f)<=.01f&&Mathf.Abs(second.sqrMagnitude-1f)<=.01f&&Mathf.Abs(Vector3.Dot(first,second))<=.01f;
        private static bool IsFinite(Vector3 value) => IsFinite(value.x)&&IsFinite(value.y)&&IsFinite(value.z);
        private static bool IsFinite(float value) => !float.IsNaN(value)&&!float.IsInfinity(value);
    }
}
