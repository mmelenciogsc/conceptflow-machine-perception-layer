// SPDX-License-Identifier: MIT OR Apache-2.0
using System;
using System.Collections.Generic;

namespace ConceptFlow.Mpl.PerceptionLab
{
    public readonly struct AuditoryIconDefinition
    {
        public readonly string Concept;
        public readonly int ConceptIndex;
        public readonly string AssetKey;
        public readonly bool Representational;
        public readonly int MaximumMilliseconds;

        public AuditoryIconDefinition(string concept,int conceptIndex,string assetKey,bool representational,int maximumMilliseconds)
        {
            Concept=concept; ConceptIndex=conceptIndex; AssetKey=assetKey;
            Representational=representational; MaximumMilliseconds=maximumMilliseconds;
        }
    }

    /** Explicit, deliberately small vocabulary shared with the headless reference. */
    public sealed class AuditoryIconRegistry
    {
        public const string FocusedObjectEvent = "event:/MachinePerception/AuditoryIcons/FocusedObject";
        private static readonly AuditoryIconDefinition Neutral =
            new("neutral",0,"procedural/neutral_presence",false,220);
        private readonly Dictionary<string,AuditoryIconDefinition> definitions =
            new(StringComparer.OrdinalIgnoreCase);

        public AuditoryIconRegistry()
        {
            Add(new("person",1,"procedural/soft_footfall_pair",true,320),"person","adult","child");
            Add(new("door",2,"procedural/restrained_latch",true,260),"door","doorway");
            Add(new("bicycle",3,"procedural/short_freewheel",true,300),"bicycle");
            Add(new("vehicle",4,"procedural/subdued_tire_texture",true,300),
                "vehicle","car","van","bus","truck","motorcycle");
        }

        public AuditoryIconDefinition Resolve(string classId)
        {
            if(string.IsNullOrWhiteSpace(classId)) return Neutral;
            return definitions.TryGetValue(classId.Trim(),out AuditoryIconDefinition value)?value:Neutral;
        }

        private void Add(AuditoryIconDefinition definition,params string[] aliases)
        {
            foreach(string alias in aliases) definitions.Add(alias,definition);
        }
    }
}
