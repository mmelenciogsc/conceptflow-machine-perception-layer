// SPDX-License-Identifier: MIT OR Apache-2.0
using System;
using UnityEngine;

namespace ConceptFlow.Mpl.PerceptionLab
{
    public sealed class FocusedObjectSonification
    {
        private const long MaximumHeadPoseAgeNs = 250_000_000L;
        private const long MaximumEntityAgeNs = 1_500_000_000L;

        private readonly IPerceptionAudioBackend backend;
        private readonly AuditoryIconRegistry registry;
        private readonly IPerceptionCoordinateFrameAdapterV1 coordinateAdapter;
        private PerceptionWorldSnapshot world;
        private PerceptionFocusSnapshot focus;
        private PerceptionHeadPoseSnapshot headPose;
        private bool hasWorld;
        private bool hasFocus;
        private bool hasHeadPose;
        private bool iconActive;
        private long renderedWorldRevision = -1;
        private long renderedFocusRevision = -1;
        private long renderedHeadSequence = -1;

        public FocusedObjectSonification(
            IPerceptionAudioBackend backend,
            AuditoryIconRegistry registry = null,
            IPerceptionCoordinateFrameAdapterV1 coordinateAdapter = null)
        {
            this.backend = backend ?? throw new ArgumentNullException(nameof(backend));
            this.registry = registry ?? new AuditoryIconRegistry();
            this.coordinateAdapter = coordinateAdapter??UnverifiedCoordinateFrameAdapterV1.Instance;
        }

        public Vector3 ListenerPosition { get; set; }
        public string ActiveTrackId { get; private set; } = string.Empty;
        public bool IsActive => iconActive;
        public string CoordinateMappingId => coordinateAdapter.MappingId;

        public void AcceptWorld(PerceptionWorldSnapshot snapshot)
        {
            world = snapshot ?? throw new ArgumentNullException(nameof(snapshot));
            hasWorld = true;
        }

        public void AcceptFocus(PerceptionFocusSnapshot snapshot)
        {
            focus = snapshot ?? throw new ArgumentNullException(nameof(snapshot));
            hasFocus = true;
        }

        public void AcceptHeadPose(PerceptionHeadPoseSnapshot snapshot)
        {
            headPose = snapshot ?? throw new ArgumentNullException(nameof(snapshot));
            hasHeadPose = snapshot.Sequence>0L && snapshot.SessionGeneration>=0L &&
                snapshot.TimestampNs>=0L && snapshot.Accuracy>=1 && snapshot.Accuracy<=3;
        }

        public bool Render(long nowNs)
        {
            if (!TryBuildCommand(nowNs, out FocusedIconCommand command, out ListenerPoseCommand listener))
            {
                Stop("invalid-or-expired-focus");
                return false;
            }

            backend.SetListenerPose(listener);
            bool unchanged = iconActive
                && renderedWorldRevision == world.Revision
                && renderedFocusRevision == focus.Revision
                && renderedHeadSequence == headPose.Sequence;
            if (!unchanged)
            {
                backend.UpsertFocusedIcon(command);
                renderedWorldRevision = world.Revision;
                renderedFocusRevision = focus.Revision;
                renderedHeadSequence = headPose.Sequence;
            }

            iconActive = true;
            ActiveTrackId = command.TrackId;
            return true;
        }

        public void Clear(string reason = "cleared")
        {
            Stop(reason);
            hasWorld = false;
            hasFocus = false;
            hasHeadPose = false;
        }

        private bool TryBuildCommand(
            long nowNs,
            out FocusedIconCommand command,
            out ListenerPoseCommand listener)
        {
            command = default;
            listener = default;
            if (!hasWorld || !hasFocus || nowNs < 0L
                || world.Revision <= 0L
                || focus.Revision <= 0L
                || world.Validity != PerceptionValidity.PerceptionReady
                || world.PublishedTimestampNs > nowNs
                || world.ValidUntilTimestampNs < nowNs
                || focus.ValidUntilTimestampNs < nowNs
                || focus.UpdatedTimestampNs > nowNs
                || !focus.HasFocus
                || string.IsNullOrWhiteSpace(focus.FocusedTrackId)
                || world.SessionGeneration != focus.SessionGeneration
                || focus.WorldRevision > world.Revision)
            {
                return false;
            }

            PerceptionEntitySnapshot entity = FindFocusedEntity();
            if (entity == null
                || !entity.HasPosition
                || !IsFinite(entity.X)
                || !IsFinite(entity.Y)
                || !IsFinite(entity.Z)
                || !IsFinite(entity.DistanceMeters)
                || !IsFinite(entity.Confidence)
                || string.IsNullOrWhiteSpace(entity.TrackId)
                || entity.OutputTimestampNs < entity.SourceCaptureTimestampNs
                || entity.OutputTimestampNs > nowNs
                || nowNs - entity.OutputTimestampNs > MaximumEntityAgeNs
                || entity.DistanceMeters <= 0f
                || entity.Confidence < 0f
                || entity.Confidence > 1f)
            {
                return false;
            }

            if (!hasHeadPose
                || headPose.SessionGeneration != world.SessionGeneration
                || !IsHeadPoseNear(headPose.TimestampNs, entity.OutputTimestampNs)
                || headPose.TimestampNs > nowNs
                || nowNs - headPose.TimestampNs > MaximumHeadPoseAgeNs
                || !IsFinite(ListenerPosition)
                || !coordinateAdapter.TryMapHeadOrientation(headPose,out Quaternion listenerRotation))
            {
                return false;
            }

            Vector3 listenerForward = listenerRotation * Vector3.forward;
            Vector3 listenerUp = listenerRotation * Vector3.up;
            if (!IsOrientation(listenerForward,listenerUp)) return false;

            AuditoryIconDefinition icon = registry.Resolve(entity.ClassId);
            Vector3 sourcePosition = new Vector3(entity.X,entity.Y,entity.Z);
            if(!coordinateAdapter.TryMapPosition(entity.Frame,sourcePosition,out Vector3 mappedPosition)) return false;
            Vector3 position;
            if (entity.Frame == PerceptionFrame.World)
            {
                position = mappedPosition;
            }
            else if (entity.Frame == PerceptionFrame.Head)
            {
                position = ListenerPosition + listenerRotation * mappedPosition;
            }
            else
            {
                // Camera coordinates are deliberately not guessed in Unity. Android must
                // supply HEAD or WORLD coordinates before a focused icon can be rendered.
                return false;
            }

            if (!IsFinite(position))
                return false;

            long sourceTimestampNs = Math.Max(entity.OutputTimestampNs, focus.UpdatedTimestampNs);
            long validUntilTimestampNs = Math.Min(world.ValidUntilTimestampNs, focus.ValidUntilTimestampNs);
            if (sourceTimestampNs < 0L || validUntilTimestampNs <= sourceTimestampNs)
                return false;

            listener = new ListenerPoseCommand(
                ListenerPosition,
                listenerForward,
                listenerUp,
                headPose.TimestampNs);

            Vector3 towardListener = ListenerPosition - position;
            Vector3 forward = towardListener.sqrMagnitude > 0.000001f
                ? towardListener.normalized
                : listenerRotation * Vector3.forward;
            Vector3 up = Vector3.ProjectOnPlane(listenerUp, forward);
            if (up.sqrMagnitude < 0.000001f)
                up = Vector3.ProjectOnPlane(Vector3.up, forward);
            if (up.sqrMagnitude < 0.000001f)
                up = Vector3.right;
            up.Normalize();

            float distanceWeight = 1f - Mathf.Clamp01(entity.DistanceMeters / 8f);
            float salience = Mathf.Clamp01((0.55f * entity.Confidence) + (0.45f * distanceWeight));
            float gain = Mathf.Clamp(0.08f + (0.2f * salience), 0.08f, 0.28f);
            AudioParameter[] parameters =
            {
                new AudioParameter("IconConcept", icon.ConceptIndex),
                new AudioParameter("IconSalience", salience),
                new AudioParameter("IconConfidence", entity.Confidence),
                new AudioParameter("DistanceMeters", Mathf.Clamp(entity.DistanceMeters,0f,8f)),
            };

            command = new FocusedIconCommand(
                entity.TrackId,
                AuditoryIconRegistry.FocusedObjectEvent,
                icon.AssetKey,
                position,
                forward,
                up,
                gain,
                sourceTimestampNs,
                validUntilTimestampNs,
                parameters);
            return true;
        }

        private PerceptionEntitySnapshot FindFocusedEntity()
        {
            for (int i = 0; i < world.Entities.Count; i++)
            {
                PerceptionEntitySnapshot candidate = world.Entities[i];
                if (string.Equals(candidate.TrackId, focus.FocusedTrackId, StringComparison.Ordinal))
                    return candidate;
            }

            return null;
        }

        private void Stop(string reason)
        {
            if (iconActive)
                backend.StopFocusedIcon(reason);
            iconActive = false;
            ActiveTrackId = string.Empty;
            renderedWorldRevision = -1;
            renderedFocusRevision = -1;
            renderedHeadSequence = -1;
        }

        private static bool IsHeadPoseNear(long headTimestampNs, long entityTimestampNs)
        {
            if (headTimestampNs < 0L || entityTimestampNs < 0L)
                return false;
            long delta = headTimestampNs >= entityTimestampNs
                ? headTimestampNs - entityTimestampNs
                : entityTimestampNs - headTimestampNs;
            return delta <= MaximumHeadPoseAgeNs;
        }

        private static bool IsFinite(Vector3 value)
        {
            return IsFinite(value.x) && IsFinite(value.y) && IsFinite(value.z);
        }

        private static bool IsOrientation(Vector3 forward,Vector3 up)
        {
            return IsFinite(forward) && IsFinite(up)
                && Mathf.Abs(forward.sqrMagnitude-1f) <= .01f
                && Mathf.Abs(up.sqrMagnitude-1f) <= .01f
                && Mathf.Abs(Vector3.Dot(forward,up)) <= .01f;
        }

        private static bool IsFinite(float value)
        {
            return !float.IsNaN(value) && !float.IsInfinity(value);
        }
    }
}
