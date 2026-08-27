// SPDX-License-Identifier: MIT OR Apache-2.0
using System;
using System.Collections.Generic;

namespace ConceptFlow.Mpl.PerceptionLab
{
    public enum NonvisualInteractionTarget { None=0, Vqa=1, Beacon=2, Back=3 }
    public enum VqaInteractionState { Idle=0, Listening=1, Processing=2, AnswerReady=3, Unavailable=4 }
    public enum BeaconInteractionState { Off=0, Selecting=1, Guiding=2, Arrived=3, Unavailable=4 }

    public readonly struct NonvisualInteractionState
    {
        public readonly bool MenuOpen;
        public readonly NonvisualInteractionTarget FocusedTarget;
        public readonly VqaInteractionState Vqa;
        public readonly BeaconInteractionState Beacon;
        public readonly bool BackAvailable;

        public NonvisualInteractionState(bool menuOpen,NonvisualInteractionTarget focusedTarget,
            VqaInteractionState vqa,BeaconInteractionState beacon,bool backAvailable)
        {
            if(!Enum.IsDefined(typeof(NonvisualInteractionTarget),focusedTarget) ||
               !Enum.IsDefined(typeof(VqaInteractionState),vqa) ||
               !Enum.IsDefined(typeof(BeaconInteractionState),beacon))
                throw new ArgumentOutOfRangeException(nameof(focusedTarget));
            MenuOpen=menuOpen; FocusedTarget=focusedTarget; Vqa=vqa; Beacon=beacon;
            BackAvailable=backAvailable;
        }
    }

    public readonly struct NonvisualAnnouncement
    {
        public readonly long Generation;
        public readonly string Text;
        public readonly bool IsDwell;
        public NonvisualAnnouncement(long generation,string text,bool isDwell)
        { Generation=generation; Text=text; IsDwell=isDwell; }
    }

    /**
     * Deterministic presenter for an externally owned interaction state. It does not
     * interpret touch events, call VQA, start navigation, or accept generated answer text.
     */
    public sealed class NonvisualInteractionPresenter
    {
        public const long DefaultDwellNs = 1_200_000_000L;
        private readonly IPerceptionAudioBackend backend;
        private readonly Queue<NonvisualAnnouncement> announcements=new();
        private readonly long dwellNs;
        private NonvisualInteractionState state;
        private bool hasState;
        private long generation;
        private long focusedAtNs;
        private bool dwellPending;
        private bool dwellSpeaking;

        public NonvisualInteractionPresenter(IPerceptionAudioBackend backend,long dwellNs=DefaultDwellNs)
        {
            if(dwellNs<=0L) throw new ArgumentOutOfRangeException(nameof(dwellNs));
            this.backend=backend??throw new ArgumentNullException(nameof(backend));
            this.dwellNs=dwellNs;
        }

        public long Generation => generation;
        public int PendingAnnouncementCount => announcements.Count;

        public void Apply(NonvisualInteractionState next,long nowNs)
        {
            if(nowNs<0L) throw new ArgumentOutOfRangeException(nameof(nowNs));
            bool targetChanged=!hasState || next.MenuOpen!=state.MenuOpen ||
                next.FocusedTarget!=state.FocusedTarget || next.BackAvailable!=state.BackAvailable;
            bool vqaChanged=hasState && next.Vqa!=state.Vqa;
            bool beaconChanged=hasState && next.Beacon!=state.Beacon;

            if(targetChanged)
            {
                if(dwellSpeaking) backend.SetDwellSpeech(generation,false,.25f);
                CancelQueuedDwell();
                generation++;
                focusedAtNs=nowNs;
                dwellSpeaking=false;
                dwellPending=next.MenuOpen && next.FocusedTarget!=NonvisualInteractionTarget.None;
                backend.EmitInterfaceState(new InterfaceAudioCommand(InterfaceStateName(next),InterfaceStateIndex(next)));
            }

            state=next;
            hasState=true;
            if(vqaChanged) EnqueueFixed("VQA, "+VqaText(next.Vqa),false);
            if(beaconChanged) EnqueueFixed("Beacon, "+BeaconText(next.Beacon),false);
        }

        public void Tick(long nowNs)
        {
            if(nowNs<0L) throw new ArgumentOutOfRangeException(nameof(nowNs));
            if(!hasState || !dwellPending || nowNs-focusedAtNs<dwellNs) return;
            dwellPending=false;
            dwellSpeaking=true;
            backend.SetDwellSpeech(generation,true,.25f);
            EnqueueFixed(FocusedText(state),true);
        }

        public bool TryTakeAnnouncement(out NonvisualAnnouncement announcement)
        {
            while(announcements.Count>0)
            {
                announcement=announcements.Dequeue();
                if(!announcement.IsDwell || announcement.Generation==generation) return true;
            }
            announcement=default; return false;
        }

        public void CompleteDwellSpeech(long completedGeneration)
        {
            if(!dwellSpeaking || completedGeneration!=generation) return;
            backend.SetDwellSpeech(completedGeneration,false,.25f);
            dwellSpeaking=false;
        }

        public void Clear()
        {
            if(dwellSpeaking) backend.SetDwellSpeech(generation,false,.25f);
            generation++;
            dwellPending=false;
            dwellSpeaking=false;
            hasState=false;
            announcements.Clear();
        }

        private void EnqueueFixed(string text,bool isDwell)
        {
            announcements.Enqueue(new NonvisualAnnouncement(generation,text,isDwell));
        }

        private void CancelQueuedDwell()
        {
            int count=announcements.Count;
            for(int index=0;index<count;index++)
            {
                NonvisualAnnouncement queued=announcements.Dequeue();
                if(!queued.IsDwell) announcements.Enqueue(queued);
            }
        }

        private static string FocusedText(NonvisualInteractionState value)
        {
            return value.FocusedTarget switch
            {
                NonvisualInteractionTarget.Vqa => "VQA, "+VqaText(value.Vqa),
                NonvisualInteractionTarget.Beacon => "Beacon, "+BeaconText(value.Beacon),
                NonvisualInteractionTarget.Back => value.BackAvailable?"Back":"Back unavailable",
                _ => string.Empty,
            };
        }

        private static string InterfaceStateName(NonvisualInteractionState value)
        {
            if(!value.MenuOpen) return "closed";
            return value.FocusedTarget switch
            {
                NonvisualInteractionTarget.Vqa => "vqa",
                NonvisualInteractionTarget.Beacon => "beacon",
                NonvisualInteractionTarget.Back => value.BackAvailable?"back":"back_unavailable",
                _ => "open",
            };
        }

        private static int InterfaceStateIndex(NonvisualInteractionState value)
        {
            if(!value.MenuOpen) return 0;
            return value.FocusedTarget switch
            {
                NonvisualInteractionTarget.None => 1,
                NonvisualInteractionTarget.Vqa => 2,
                NonvisualInteractionTarget.Beacon => 3,
                NonvisualInteractionTarget.Back => value.BackAvailable?4:5,
                _ => 0,
            };
        }

        private static string VqaText(VqaInteractionState value) => value switch
        {
            VqaInteractionState.Idle => "ready",
            VqaInteractionState.Listening => "listening",
            VqaInteractionState.Processing => "processing",
            VqaInteractionState.AnswerReady => "answer ready",
            _ => "unavailable",
        };

        private static string BeaconText(BeaconInteractionState value) => value switch
        {
            BeaconInteractionState.Off => "off",
            BeaconInteractionState.Selecting => "selecting destination",
            BeaconInteractionState.Guiding => "guidance active",
            BeaconInteractionState.Arrived => "arrived",
            _ => "unavailable",
        };
    }
}
