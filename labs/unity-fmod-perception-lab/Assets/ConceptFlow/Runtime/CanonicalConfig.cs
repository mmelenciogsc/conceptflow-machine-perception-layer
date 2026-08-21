// SPDX-License-Identifier: MIT OR Apache-2.0
using System;
using UnityEngine;

namespace ConceptFlow.Mpl.PerceptionLab
{
    [Serializable]
    public sealed class CanonicalConfig
    {
        public int schemaVersion;
        public float bubbleRadiusMeters;
        public BodyProfileData bodyProfile = new();
        public SpeakerArrayData speakerArray = new();

        public static CanonicalConfig Load()
        {
            TextAsset asset = Resources.Load<TextAsset>("perception_math");
            if (asset == null) throw new InvalidOperationException("Missing canonical perception_math resource.");
            CanonicalConfig value = JsonUtility.FromJson<CanonicalConfig>(asset.text);
            if (value == null || value.schemaVersion != 1 || Mathf.Abs(value.bubbleRadiusMeters - 0.9144f) > 1e-6f)
                throw new InvalidOperationException("Unsupported or invalid perception math configuration.");
            return value;
        }
    }

    [Serializable]
    public sealed class BodyProfileData
    {
        public float statureMeters = 1.70f;
        public float shoulderWidthMeters = 0.42f;
        public float torsoDepthMeters = 0.24f;
        public float hipWidthMeters = 0.34f;
        public float headRadiusMeters = 0.105f;
    }

    [Serializable]
    public sealed class SpeakerArrayData
    {
        public string[] banks = Array.Empty<string>();
        public float[] ringAnglesDegrees = Array.Empty<float>();
        public int samplesPerRing = 12;
        public float angularConcentration = 8f;
        public float continuity = 0.30f;
    }
}
