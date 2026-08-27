// SPDX-License-Identifier: MIT OR Apache-2.0
using System;
using System.Collections.Generic;
using System.Linq;

namespace ConceptFlow.Mpl.PerceptionLab
{
    public enum PerceptualPriorityLane
    {
        PeriodicScene=10, OrdinaryIcon=30, SalientIcon=50, Beacon=60,
        UserSpeech=70, HapticTransition=80, Geometry=90, RapidGeometry=100,
    }

    public readonly struct ScheduledPerceptualCue
    {
        public readonly string CueId;
        public readonly string DeduplicationKey;
        public readonly PerceptualPriorityLane Lane;
        public readonly long CreatedNs;
        public readonly long ExpiryNs;
        public readonly int DurationMs;
        public readonly object Payload;
        public readonly float DuckOthers;
        public readonly int AudioVoiceCost;
        public readonly int SpeechSlotCost;
        public readonly int HapticSlotCost;

        public ScheduledPerceptualCue(string cueId,string deduplicationKey,PerceptualPriorityLane lane,
            long createdNs,long expiryNs,int durationMs,object payload,float duckOthers=0f,
            int audioVoiceCost=1,int speechSlotCost=0,int hapticSlotCost=0)
        {
            if(string.IsNullOrWhiteSpace(cueId)||string.IsNullOrWhiteSpace(deduplicationKey)||
               createdNs<0||expiryNs<=createdNs||durationMs<=0||duckOthers<0f||duckOthers>1f||
               audioVoiceCost<0||speechSlotCost<0||hapticSlotCost<0||
               audioVoiceCost+speechSlotCost+hapticSlotCost<=0) throw new ArgumentException("Invalid scheduled cue.");
            CueId=cueId; DeduplicationKey=deduplicationKey; Lane=lane; CreatedNs=createdNs;
            ExpiryNs=expiryNs; DurationMs=durationMs; Payload=payload; DuckOthers=duckOthers;
            AudioVoiceCost=audioVoiceCost; SpeechSlotCost=speechSlotCost; HapticSlotCost=hapticSlotCost;
        }
    }

    public sealed class SchedulerCounters
    {
        public int Generated;
        public int Rendered;
        public int SuppressedCooldown;
        public int SuppressedCapacity;
        public int Superseded;
        public int Expired;
    }

    /** Deterministic bounded scheduler; cue lifecycle remains the renderer's responsibility. */
    public sealed class PerceptualPriorityScheduler
    {
        private readonly int maximumAudioVoices;
        private readonly int maximumSpeechSlots;
        private readonly int maximumHapticSlots;
        private readonly long cooldownNs;
        private readonly Dictionary<string,ScheduledPerceptualCue> pending=new(StringComparer.Ordinal);
        private readonly Dictionary<string,long> lastRendered=new(StringComparer.Ordinal);

        public SchedulerCounters Counters { get; } = new();
        public int PendingCount => pending.Count;

        public PerceptualPriorityScheduler(int maximumAudioVoices=6,int maximumSpeechSlots=1,
            int maximumHapticSlots=1,int cooldownMilliseconds=180)
        {
            if(maximumAudioVoices<=0||maximumSpeechSlots<=0||maximumHapticSlots<=0||cooldownMilliseconds<0)
                throw new ArgumentOutOfRangeException(nameof(maximumAudioVoices));
            this.maximumAudioVoices=maximumAudioVoices; this.maximumSpeechSlots=maximumSpeechSlots;
            this.maximumHapticSlots=maximumHapticSlots; cooldownNs=cooldownMilliseconds*1_000_000L;
        }

        public bool Submit(ScheduledPerceptualCue cue,long nowNs)
        {
            Counters.Generated++;
            if(nowNs>cue.ExpiryNs) { Counters.Expired++; return false; }
            if(lastRendered.TryGetValue(cue.DeduplicationKey,out long last)&&nowNs-last<cooldownNs)
            { Counters.SuppressedCooldown++; return false; }
            if(pending.TryGetValue(cue.DeduplicationKey,out ScheduledPerceptualCue previous))
            {
                Counters.Superseded++;
                if((int)cue.Lane<(int)previous.Lane ||
                   ((int)cue.Lane==(int)previous.Lane&&cue.CreatedNs<=previous.CreatedNs)) return false;
            }
            pending[cue.DeduplicationKey]=cue; return true;
        }

        public IReadOnlyList<ScheduledPerceptualCue> Dispatch(long nowNs)
        {
            foreach(string key in pending.Where(item=>nowNs>item.Value.ExpiryNs).Select(item=>item.Key).ToArray())
            { pending.Remove(key); Counters.Expired++; }
            ScheduledPerceptualCue[] ordered=pending.Values.OrderByDescending(item=>(int)item.Lane)
                .ThenBy(item=>item.CreatedNs).ThenBy(item=>item.CueId,StringComparer.Ordinal).ToArray();
            var chosen=new List<ScheduledPerceptualCue>(ordered.Length);
            int voices=0,speech=0,haptics=0;
            foreach(ScheduledPerceptualCue cue in ordered)
            {
                if(voices+cue.AudioVoiceCost>maximumAudioVoices||speech+cue.SpeechSlotCost>maximumSpeechSlots||
                   haptics+cue.HapticSlotCost>maximumHapticSlots) { Counters.SuppressedCapacity++; continue; }
                chosen.Add(cue); voices+=cue.AudioVoiceCost; speech+=cue.SpeechSlotCost; haptics+=cue.HapticSlotCost;
            }
            foreach(ScheduledPerceptualCue cue in chosen)
            { pending.Remove(cue.DeduplicationKey); lastRendered[cue.DeduplicationKey]=nowNs; }
            Counters.Rendered+=chosen.Count; return chosen;
        }

        public void Clear() => pending.Clear();
    }
}
