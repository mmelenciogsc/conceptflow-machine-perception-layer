// SPDX-License-Identifier: MIT OR Apache-2.0
using System;
using UnityEngine;

namespace ConceptFlow.Mpl.PerceptionLab
{
    public sealed class FocusedObjectSonification
    {
        private const long MaximumHeadPoseAgeNs = 250_000_000L;
        private const long MaximumEntityAgeNs = 1_500_000_000L;
        private const long BeaconPulseIntervalNs = 1_500_000_000L;

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
        private long renderedBeaconActivationId = -1;
        private long nextBeaconPulseTimestampNs;

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
            long worldRevision=hasWorld?world.Revision:-1L;
            long beaconActivation=focus.Beacon?.ActivationId??-1L;
            bool beaconPulseDue=beaconActivation>0L &&
                (renderedBeaconActivationId!=beaconActivation || nowNs>=nextBeaconPulseTimestampNs);
            bool unchanged = iconActive
                && renderedWorldRevision == worldRevision
                && renderedFocusRevision == focus.Revision
                && renderedHeadSequence == headPose.Sequence
                && renderedBeaconActivationId == beaconActivation
                && !beaconPulseDue;
            if (!unchanged)
            {
                if(beaconPulseDue && iconActive) backend.StopFocusedIcon("beacon-pulse");
                backend.UpsertFocusedIcon(command);
                renderedWorldRevision = worldRevision;
                renderedFocusRevision = focus.Revision;
                renderedHeadSequence = headPose.Sequence;
                renderedBeaconActivationId = beaconActivation;
                if(beaconPulseDue) nextBeaconPulseTimestampNs=nowNs+BeaconPulseIntervalNs;
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
            if (!hasFocus || nowNs < 0L
                || focus.Revision <= 0L
                || focus.ValidUntilTimestampNs < nowNs
                || focus.UpdatedTimestampNs > nowNs
                || !focus.HasFocus
                || string.IsNullOrWhiteSpace(focus.FocusedTrackId))
            {
                return false;
            }

            if(focus.Mode==PerceptionFocusMode.BeaconActive && focus.Beacon!=null)
                return TryBuildBeaconCommand(nowNs,out command,out listener);

            if (!hasWorld || world.Revision <= 0L
                || world.Validity != PerceptionValidity.PerceptionReady
                || world.PublishedTimestampNs > nowNs
                || world.ValidUntilTimestampNs < nowNs
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
                new AudioParameter("BeaconMode", 0f),
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

        private bool TryBuildBeaconCommand(
            long nowNs,
            out FocusedIconCommand command,
            out ListenerPoseCommand listener)
        {
            command=default; listener=default;
            PerceptionBeaconSnapshot beacon=focus.Beacon;
            if(beacon==null || beacon.ActivatedTimestampNs<0L || beacon.ActivatedTimestampNs>nowNs ||
               beacon.ValidUntilTimestampNs<=beacon.ActivatedTimestampNs ||
               beacon.ValidUntilTimestampNs!=focus.ValidUntilTimestampNs ||
               beacon.ValidUntilTimestampNs<nowNs ||
               beacon.ActivationId<=0L || beacon.SourceFrameId<=0L ||
               beacon.SourceCaptureTimestampNs<0L ||
               beacon.SourceCaptureTimestampNs>beacon.ActivatedTimestampNs ||
               beacon.DistanceMeters<=0f ||
               !IsFinite(beacon.DistanceMeters) || !IsFinite(beacon.Confidence) ||
               beacon.Confidence<0f || beacon.Confidence>1f ||
               string.IsNullOrWhiteSpace(beacon.TrackId) || string.IsNullOrWhiteSpace(beacon.ClassId) ||
               beacon.TrackId!=focus.FocusedTrackId || !hasHeadPose ||
               headPose.SessionGeneration!=focus.SessionGeneration || headPose.TimestampNs>nowNs ||
               nowNs-headPose.TimestampNs>MaximumHeadPoseAgeNs || !IsFinite(ListenerPosition) ||
               !coordinateAdapter.TryMapHeadOrientation(headPose,out Quaternion listenerRotation)) return false;

            Vector3 listenerForward=listenerRotation*Vector3.forward;
            Vector3 listenerUp=listenerRotation*Vector3.up;
            if(!IsOrientation(listenerForward,listenerUp)) return false;
            Vector3 source=new(beacon.X,beacon.Y,beacon.Z);
            Vector3 position;
            if(beacon.AnchorMode==PerceptionBeaconAnchorMode.WorldAnchored)
            {
                if(!coordinateAdapter.TryMapPosition(PerceptionFrame.World,source,out position)) return false;
            }
            else if(beacon.AnchorMode==PerceptionBeaconAnchorMode.OrientationStabilizedRelative)
            {
                if(!beacon.HasReferenceHeadOrientation || beacon.ReferenceHeadTimestampNs<0L ||
                   beacon.ReferenceHeadTimestampNs>beacon.ActivatedTimestampNs ||
                   beacon.ActivatedTimestampNs-beacon.ReferenceHeadTimestampNs>MaximumHeadPoseAgeNs ||
                   !coordinateAdapter.TryMapPosition(PerceptionFrame.Head,source,out Vector3 mappedVector)) return false;
                var reference=new PerceptionHeadPoseSnapshot
                {
                    Sequence=beacon.ActivationId,
                    SessionGeneration=focus.SessionGeneration,
                    TimestampNs=beacon.ReferenceHeadTimestampNs,
                    Accuracy=beacon.ReferenceHeadAccuracy,
                    W=beacon.ReferenceHeadW, X=beacon.ReferenceHeadX,
                    Y=beacon.ReferenceHeadY, Z=beacon.ReferenceHeadZ,
                };
                if(!coordinateAdapter.TryMapHeadOrientation(reference,out Quaternion referenceRotation)) return false;
                // Translation is intentionally unavailable: the origin follows the listener while
                // the captured orientation keeps the bearing stable through subsequent head turns.
                position=ListenerPosition+referenceRotation*mappedVector;
            }
            else return false;
            if(!IsFinite(position)) return false;

            listener=new ListenerPoseCommand(ListenerPosition,listenerForward,listenerUp,headPose.TimestampNs);
            Vector3 towardListener=ListenerPosition-position;
            Vector3 forward=towardListener.sqrMagnitude>.000001f?towardListener.normalized:listenerForward;
            Vector3 up=Vector3.ProjectOnPlane(listenerUp,forward);
            if(up.sqrMagnitude<.000001f) up=Vector3.ProjectOnPlane(Vector3.up,forward);
            if(up.sqrMagnitude<.000001f) up=Vector3.right;
            up.Normalize();
            AuditoryIconDefinition icon=registry.Resolve(beacon.ClassId);
            float distanceWeight=1f-Mathf.Clamp01(beacon.DistanceMeters/8f);
            float salience=Mathf.Clamp01(.55f*beacon.Confidence+.45f*distanceWeight);
            float gain=Mathf.Clamp(.08f+.18f*salience,.08f,.26f);
            AudioParameter[] parameters=
            {
                new AudioParameter("IconConcept",icon.ConceptIndex),
                new AudioParameter("IconSalience",salience),
                new AudioParameter("IconConfidence",beacon.Confidence),
                new AudioParameter("DistanceMeters",Mathf.Clamp(beacon.DistanceMeters,0f,8f)),
                new AudioParameter("BeaconMode",(float)beacon.AnchorMode),
            };
            command=new FocusedIconCommand(
                beacon.TrackId,AuditoryIconRegistry.FocusedObjectEvent,icon.AssetKey,position,forward,up,
                gain,beacon.ActivatedTimestampNs,beacon.ValidUntilTimestampNs,parameters);
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
            renderedBeaconActivationId = -1;
            nextBeaconPulseTimestampNs = 0L;
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
