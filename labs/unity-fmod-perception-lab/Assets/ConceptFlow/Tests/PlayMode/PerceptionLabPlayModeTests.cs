// SPDX-License-Identifier: MIT OR Apache-2.0
using System.Collections;
using NUnit.Framework;
using UnityEngine;
using UnityEngine.TestTools;

namespace ConceptFlow.Mpl.PerceptionLab.Tests
{
    public sealed class PerceptionLabPlayModeTests
    {
        [UnityTest]
        public IEnumerator BroadWallScenarioProducesTextualMetricState()
        {
            var root=new GameObject("Perception lab test root");
            var controller=root.AddComponent<PerceptionLabController>();
            controller.BuildScenario(LabScenario.BroadWall);
            yield return null;
            StringAssert.Contains("BroadWall",controller.Status);
            StringAssert.Contains("clearance",controller.Status);
            StringAssert.Contains("proximity",controller.Status);
            Object.Destroy(root);
        }
    }
}
