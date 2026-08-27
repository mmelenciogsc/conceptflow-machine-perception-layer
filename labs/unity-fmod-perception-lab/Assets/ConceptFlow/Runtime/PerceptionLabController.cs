// SPDX-License-Identifier: MIT OR Apache-2.0
using System;
using System.Collections.Generic;
using System.IO;
using System.Text;
using UnityEngine;

namespace ConceptFlow.Mpl.PerceptionLab
{
    public enum LabScenario
    {
        NarrowCorridor, BroadWall, Doorway, Pillar, TableAndChair, LowObstruction,
        ShoulderObstruction, HeadOverhang, CeilingFeature, StepAndDrop, Stairs,
        StaticPedestrian, ApproachingPedestrian, CrossingPedestrian, LargeMovingObject,
        ClutteredRoom, OutdoorWalkway, NoisyAmbient
    }

    public sealed class PerceptionLabController : MonoBehaviour
    {
        private const int ColliderCapacity = 128;
        private readonly Collider[] overlaps = new Collider[ColliderCapacity];
        private readonly List<GameObject> scenarioObjects = new(64);
        private CanonicalConfig config = null!;
        private BodySurfaceField body = null!;
        private VirtualSpeakerField speakers = null!;
        private IPerceptionAudioBackend audioBackend = null!;
        private IPerceptionSnapshotSource perceptionSource;
        private IPerceptionCoordinateFrameAdapterV1 coordinateAdapter = null!;
        private bool ownsPerceptionSource;
        private bool ownsAudioBackend;
        private FocusedObjectSonification focusedObject = null!;
        private NonvisualInteractionPresenter interactionPresenter = null!;
        private Transform bodyFrame = null!;
        private Transform headFrame = null!;
        private Vector3 nearestGeometry;
        private Clearance nearestClearance;
        private float[] activeWeights = Array.Empty<float>();
        private string status = "Initializing";
        private bool paused;
        private float scenarioTime;
        private int scenarioIndex;
        private float previousClearance = float.NaN;
        private float motionActivation = .12f;
        private float nextDispatchTime;
        private readonly int[] strongestEmitterIndices = new int[5];
        private readonly float[] strongestEmitterWeights = new float[5];

        public string Status => status;
        public int LastAudioCommandCount { get; private set; }
        public string LastHapticState { get; private set; } = "none";
        public string ActiveFocusedTrackId => focusedObject?.ActiveTrackId??string.Empty;
        public int ActiveFocusedIconCount => focusedObject?.IsActive==true?1:0;
        public int PendingNonvisualAnnouncements => interactionPresenter?.PendingAnnouncementCount??0;
        public string CoordinateMappingId => focusedObject?.CoordinateMappingId??string.Empty;

        private void Awake()
        {
            config = CanonicalConfig.Load();
            body = new BodySurfaceField(config);
            speakers = new VirtualSpeakerField(body, config);
            bodyFrame = new GameObject("BODY frame").transform;
            headFrame = new GameObject("HEAD/listener frame").transform;
            headFrame.SetParent(bodyFrame, false);
            headFrame.localPosition = new Vector3(0, 1.60f, 0);
            // Android Node publishes the repository's canonical frame. This
            // adapter performs only the documented handedness conversion.
            coordinateAdapter=CanonicalProtocolCoordinateFrameAdapterV1.Instance;
#if CONCEPTFLOW_FMOD_UNITY
            audioBackend=new FmodStudioPerceptionAudioBackend(); ownsAudioBackend=true;
#else
            audioBackend=new InspectableFmodBackend();
#endif
#if UNITY_ANDROID && !UNITY_EDITOR
            perceptionSource=new AndroidPerceptionBridgeClient(); ownsPerceptionSource=true;
#endif
            RebuildPresenters();
            BuildScenario((LabScenario)scenarioIndex);
            AnnounceControls();
        }

        // Ordering, Android polling, focus selection, and raw touch interpretation stay
        // outside this lab controller. A host injects already-decoded snapshots explicitly.
        public void ApplyWorldSnapshot(PerceptionWorldSnapshot snapshot) => focusedObject.AcceptWorld(snapshot);
        public void ApplyFocusSnapshot(PerceptionFocusSnapshot snapshot) => focusedObject.AcceptFocus(snapshot);
        public void ApplyHeadPoseSnapshot(PerceptionHeadPoseSnapshot snapshot)
        {
            focusedObject.ListenerPosition=headFrame.position;
            focusedObject.AcceptHeadPose(snapshot);
        }
        public bool RenderFocusedObject(long nowNs)
        {
            focusedObject.ListenerPosition=headFrame.position;
            return focusedObject.Render(nowNs);
        }
        public void ApplyInteractionState(NonvisualInteractionState next,long nowNs) => interactionPresenter.Apply(next,nowNs);
        public void TickInteractionPresenter(long nowNs) => interactionPresenter.Tick(nowNs);
        public bool TryTakeNonvisualAnnouncement(out NonvisualAnnouncement announcement) =>
            interactionPresenter.TryTakeAnnouncement(out announcement);
        public void CompleteDwellSpeech(long generation) => interactionPresenter.CompleteDwellSpeech(generation);

        private void Update()
        {
            if (Input.GetKeyDown(KeyCode.H)) AnnounceControls();
            if (Input.GetKeyDown(KeyCode.Space)) { paused = !paused; Debug.Log($"[MPL_LAB] paused={paused}"); }
            if (Input.GetKeyDown(KeyCode.R)) BuildScenario((LabScenario)scenarioIndex);
            if (Input.GetKeyDown(KeyCode.E)) ExportText();
            if (Input.GetKeyDown(KeyCode.LeftBracket)) ChangeScenario(-1);
            if (Input.GetKeyDown(KeyCode.RightBracket)) ChangeScenario(1);
            for (int key=0;key<=9;key++)
                if (Input.GetKeyDown((KeyCode)((int)KeyCode.Alpha0+key))) { scenarioIndex = key % Enum.GetValues(typeof(LabScenario)).Length; BuildScenario((LabScenario)scenarioIndex); }
            if (!paused) scenarioTime += Time.deltaTime;
            AnimateScenario((LabScenario)scenarioIndex, scenarioTime);
            QueryGeometry();
            PollPerceptionBridge();
        }

        public void ConfigurePerceptionRuntime(IPerceptionSnapshotSource source,
            IPerceptionAudioBackend backend,IPerceptionCoordinateFrameAdapterV1 frameAdapter,
            bool takeSourceOwnership=false,bool takeBackendOwnership=false)
        {
            focusedObject?.Clear("runtime-reconfigured");
            interactionPresenter?.Clear();
            if(ownsPerceptionSource) perceptionSource?.Dispose();
            if(ownsAudioBackend && audioBackend is IDisposable disposableBackend) disposableBackend.Dispose();
            perceptionSource=source; audioBackend=backend??throw new ArgumentNullException(nameof(backend));
            coordinateAdapter=frameAdapter??throw new ArgumentNullException(nameof(frameAdapter));
            ownsPerceptionSource=takeSourceOwnership; ownsAudioBackend=takeBackendOwnership;
            RebuildPresenters();
        }

        private void RebuildPresenters()
        {
            focusedObject=new FocusedObjectSonification(audioBackend,coordinateAdapter:coordinateAdapter)
                { ListenerPosition=headFrame.position };
            interactionPresenter=new NonvisualInteractionPresenter(audioBackend);
        }

        private void PollPerceptionBridge()
        {
            if(perceptionSource==null) return;
            if(perceptionSource.TryPollHeadPose(out PerceptionHeadPoseSnapshot head)) ApplyHeadPoseSnapshot(head);
            if(perceptionSource.TryPoll(out PerceptionWorldSnapshot world)) ApplyWorldSnapshot(world);
            if(perceptionSource.TryPollFocus(out PerceptionFocusSnapshot focus)) ApplyFocusSnapshot(focus);
            if(perceptionSource.TryGetMonotonicTimestampNs(out long nowNs)) RenderFocusedObject(nowNs);
        }

        private void QueryGeometry()
        {
            int count = Physics.OverlapSphereNonAlloc(bodyFrame.position + Vector3.up*.9f, 2.2f, overlaps, ~0, QueryTriggerInteraction.Ignore);
            float best = float.PositiveInfinity;
            Collider bestCollider = null;
            Vector3 bestPoint = default;
            for (int i=0;i<count;i++)
            {
                Collider candidate = overlaps[i];
                if (candidate.transform.IsChildOf(bodyFrame)) continue;
                Vector3 localProbe = bodyFrame.TransformPoint(new Vector3(0, .95f, 0));
                Vector3 point = candidate.ClosestPoint(localProbe);
                Clearance clearance = body.Evaluate(bodyFrame.InverseTransformPoint(point));
                if (clearance.Distance < best) { best=clearance.Distance; bestCollider=candidate; bestPoint=point; nearestClearance=clearance; }
            }
            if (bestCollider == null)
            {
                status="No metric geometry in query range"; LastAudioCommandCount=0; LastHapticState="none";
                if(activeWeights.Length>0) Array.Clear(activeWeights,0,activeWeights.Length);
                previousClearance=float.NaN; return;
            }
            nearestGeometry=bestPoint;
            Vector3 direction = bodyFrame.InverseTransformPoint(bestPoint)-nearestClearance.SurfacePoint;
            if(activeWeights.Length!=speakers.Emitters.Count) activeWeights=new float[speakers.Emitters.Count];
            speakers.Weights(direction,activeWeights);
            float delta=Mathf.Max(Time.unscaledDeltaTime,.0001f);
            float approach=float.IsNaN(previousClearance)?0f:Mathf.Max(0f,(previousClearance-best)/delta);
            previousClearance=best;
            float target=Mathf.Max(.12f,Mathf.Clamp01(approach));
            motionActivation=Mathf.Lerp(motionActivation,target,target>motionActivation?.65f:.18f);
            if(Time.unscaledTime>=nextDispatchTime)
            {
                DispatchInspectableLayers(direction,approach);
                nextDispatchTime=Time.unscaledTime+.25f;
            }
            status=$"{((LabScenario)scenarioIndex)} | {bestCollider.name} | region {nearestClearance.Region} | clearance {best:F3} m | proximity {nearestClearance.Proximity:F3} | motion {motionActivation:F3} | voices {LastAudioCommandCount} | haptic {LastHapticState}";
        }

        private void DispatchInspectableLayers(Vector3 direction,float approach)
        {
            float definition=nearestClearance.Proximity*motionActivation;
            float soundSize=.06f+(.65f-.06f)*nearestClearance.Proximity;
            float anchorGain=.08f+(.34f-.08f)*definition*.35f;
            audioBackend.Dispatch(new SpatialAudioCommand(InspectableFmodBackend.AnchorEvent,"Intrusion Anchor",nearestGeometry,-direction.normalized,anchorGain,soundSize));
            SelectStrongestEmitters();
            float selectedTotal=0f;
            for(int i=0;i<strongestEmitterIndices.Length;i++) if(strongestEmitterIndices[i]>=0) selectedTotal+=strongestEmitterWeights[i];
            float fieldGain=.24f*definition*definition;
            LastAudioCommandCount=1;
            if(selectedTotal>0f&&fieldGain>0f)
            {
                for(int i=0;i<strongestEmitterIndices.Length;i++)
                {
                    int index=strongestEmitterIndices[i]; if(index<0) continue;
                    SpeakerEmitter emitter=speakers.Emitters[index];
                    audioBackend.Dispatch(new SpatialAudioCommand(InspectableFmodBackend.FieldEvent,"Envelopment Field",bodyFrame.TransformPoint(emitter.Position),emitter.InwardNormal,fieldGain*strongestEmitterWeights[i]/selectedTotal,soundSize));
                    LastAudioCommandCount++;
                }
            }
            if(nearestClearance.Proximity>.05f||approach>.15f)
            {
                string pattern=approach>.8f?"urgent-approach":"approach";
                float intensity=Mathf.Clamp01(.15f+.65f*definition+.20f*Mathf.Min(approach,1f));
                LastHapticState=$"single-actuator/non-spatial; pattern={pattern}; intensity={intensity:F3}";
            }
            else LastHapticState="none";
        }

        private void SelectStrongestEmitters()
        {
            for(int slot=0;slot<strongestEmitterIndices.Length;slot++) { strongestEmitterIndices[slot]=-1; strongestEmitterWeights[slot]=-1f; }
            for(int index=0;index<activeWeights.Length;index++)
            {
                float weight=activeWeights[index];
                for(int slot=0;slot<strongestEmitterIndices.Length;slot++)
                {
                    if(weight<=strongestEmitterWeights[slot]) continue;
                    for(int shift=strongestEmitterIndices.Length-1;shift>slot;shift--) { strongestEmitterIndices[shift]=strongestEmitterIndices[shift-1]; strongestEmitterWeights[shift]=strongestEmitterWeights[shift-1]; }
                    strongestEmitterIndices[slot]=index; strongestEmitterWeights[slot]=weight; break;
                }
            }
        }

        private void ChangeScenario(int delta)
        {
            int count=Enum.GetValues(typeof(LabScenario)).Length;
            scenarioIndex=(scenarioIndex+delta+count)%count;
            BuildScenario((LabScenario)scenarioIndex);
        }

        public void BuildScenario(LabScenario scenario)
        {
            scenarioIndex=(int)scenario;
            foreach(GameObject item in scenarioObjects) if(item!=null) { if(Application.isPlaying) Destroy(item); else DestroyImmediate(item); }
            scenarioObjects.Clear(); scenarioTime=0f; previousClearance=float.NaN; motionActivation=.12f; nextDispatchTime=0f; LastAudioCommandCount=0; LastHapticState="none";
            switch(scenario)
            {
                case LabScenario.NarrowCorridor: Box("Left wall",new(-.85f,1,1),new(.12f,2,5)); Box("Right wall",new(.85f,1,1),new(.12f,2,5)); break;
                case LabScenario.BroadWall: Box("Broad wall",new(-.70f,1,0),new(.10f,2.2f,4)); break;
                case LabScenario.Doorway: Box("Doorframe left",new(-.55f,1,1),new(.14f,2,.18f)); Box("Doorframe right",new(.55f,1,1),new(.14f,2,.18f)); Box("Lintel",new(0,2,1),new(1.2f,.14f,.18f)); break;
                case LabScenario.Pillar: Box("Narrow pillar",new(.48f,1,.48f),new(.16f,2,.16f)); break;
                case LabScenario.TableAndChair: Box("Table edge",new(0,.78f,.55f),new(1.1f,.08f,.12f)); Box("Chair",new(-.55f,.45f,1.1f),new(.45f,.9f,.45f)); break;
                case LabScenario.LowObstruction: Box("Low obstruction",new(0,.18f,.55f),new(.55f,.36f,.25f)); break;
                case LabScenario.ShoulderObstruction: Box("Shoulder obstruction",new(-.45f,1.35f,.25f),new(.18f,.35f,.18f)); break;
                case LabScenario.HeadOverhang: Box("Head overhang",new(0,1.95f,.2f),new(.8f,.12f,.8f)); break;
                case LabScenario.CeilingFeature: Box("Ceiling feature",new(.3f,2.1f,.7f),new(.4f,.18f,.4f)); break;
                case LabScenario.StepAndDrop: Box("Step",new(0,.08f,.75f),new(1,.16f,.5f)); break;
                case LabScenario.Stairs: for(int i=0;i<4;i++) Box($"Stair {i}",new(0,.08f+i*.16f,.8f+i*.32f),new(1,.16f,.32f)); break;
                case LabScenario.StaticPedestrian: Capsule("Static pedestrian",new(.25f,.9f,.8f),.25f,1.8f); break;
                case LabScenario.ApproachingPedestrian: Capsule("Approaching pedestrian",new(0,.9f,2.8f),.25f,1.8f); break;
                case LabScenario.CrossingPedestrian: Capsule("Crossing pedestrian",new(-2,.9f,.8f),.25f,1.8f); break;
                case LabScenario.LargeMovingObject: Box("Large moving object",new(0,.8f,3),new(1.4f,1.6f,.5f)); break;
                case LabScenario.ClutteredRoom: for(int x=-2;x<=2;x++) for(int z=1;z<=3;z++) Box($"Clutter {x} {z}",new(x*.45f,.35f,z*.55f),new(.25f,.7f,.25f)); break;
                case LabScenario.OutdoorWalkway: Box("Walkway left edge",new(-1,.2f,2),new(.15f,.4f,5)); Box("Outdoor pole",new(.6f,1,1.2f),new(.12f,2,.12f)); break;
                case LabScenario.NoisyAmbient: Box("Ambient test wall",new(.7f,1,.2f),new(.12f,2,3)); break;
            }
            Debug.Log($"[MPL_LAB] scenario={scenario}; all controls and results are also textual");
        }

        private void AnimateScenario(LabScenario scenario, float time)
        {
            if (scenarioObjects.Count == 0) return;
            if (scenario == LabScenario.ApproachingPedestrian || scenario == LabScenario.LargeMovingObject)
                scenarioObjects[0].transform.position = new Vector3(0, scenarioObjects[0].transform.position.y, Mathf.Max(.45f, 2.8f-time*.45f));
            else if (scenario == LabScenario.CrossingPedestrian)
                scenarioObjects[0].transform.position = new Vector3(-2f+(time*.6f)%4f, .9f, .8f);
        }

        private void Box(string name, Vector3 position, Vector3 size)
        { GameObject item=GameObject.CreatePrimitive(PrimitiveType.Cube); item.name=name; item.transform.SetPositionAndRotation(position,Quaternion.identity); item.transform.localScale=size; scenarioObjects.Add(item); }
        private void Capsule(string name, Vector3 position, float radius, float height)
        { GameObject item=GameObject.CreatePrimitive(PrimitiveType.Capsule); item.name=name; item.transform.position=position; item.transform.localScale=new Vector3(radius*2,height*.5f,radius*2); scenarioObjects.Add(item); }

        private void AnnounceControls() => Debug.Log("[MPL_LAB] Keys 0-9 select scenes; [ and ] cycle all 18 scenes; Space pauses; R restarts; E exports accessible text; H repeats help.");
        private void ExportText()
        {
            string path=Path.Combine(Application.persistentDataPath,"conceptflow-perception-lab.txt");
            File.WriteAllText(path,status+Environment.NewLine+$"audioCommands={LastAudioCommandCount}; haptic={LastHapticState}"+Environment.NewLine+"Supplemental awareness only; preserve ordinary environmental hearing."+Environment.NewLine,Encoding.UTF8);
            Debug.Log($"[MPL_LAB] exported={path}");
        }

        private void OnGUI()
        {
            GUI.color=new Color(.972f,.980f,.984f); GUI.Label(new Rect(24,24,Screen.width-48,28),"CONCEPTFlow — Machine Perception Layer");
            GUI.Label(new Rect(24,52,Screen.width-48,28),status);
            GUI.Label(new Rect(24,80,Screen.width-48,28),"Map. Morph. Move. — It's just supplemental awareness.");
        }

        private void OnDrawGizmos()
        {
            if(bodyFrame==null||body==null) return;
            Gizmos.color=new Color(.65f,.64f,.43f,.55f);
            foreach(BodyCapsule segment in body.Segments)
            {
                Gizmos.DrawWireSphere(bodyFrame.TransformPoint(segment.Start),segment.Radius+body.BubbleRadiusMeters);
                Gizmos.DrawWireSphere(bodyFrame.TransformPoint(segment.End),segment.Radius+body.BubbleRadiusMeters);
                Gizmos.DrawLine(bodyFrame.TransformPoint(segment.Start),bodyFrame.TransformPoint(segment.End));
            }
            Gizmos.DrawLine(bodyFrame.TransformPoint(nearestClearance.SurfacePoint),nearestGeometry);
        }

        private void OnDestroy()
        {
            focusedObject?.Clear("controller-destroyed");
            interactionPresenter?.Clear();
            if(ownsPerceptionSource) perceptionSource?.Dispose();
            if(ownsAudioBackend && audioBackend is IDisposable disposableBackend) disposableBackend.Dispose();
        }
    }
}
