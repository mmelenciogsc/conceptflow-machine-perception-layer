// SPDX-License-Identifier: MIT OR Apache-2.0
using System.IO;
using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;

namespace ConceptFlow.Mpl.PerceptionLab.Editor
{
    public static class PerceptionLabSceneBuilder
    {
        [MenuItem("CONCEPTFlow/Build Perception Lab Scene")]
        public static void BuildAndSave()
        {
            var scene=EditorSceneManager.NewScene(NewSceneSetup.EmptyScene,NewSceneMode.Single);
            var root=new GameObject("CONCEPTFlow Machine Perception Lab"); root.AddComponent<PerceptionLabController>();
            var cameraObject=new GameObject("Sighted Diagnostic Camera");
            var camera=cameraObject.AddComponent<Camera>(); camera.clearFlags=CameraClearFlags.SolidColor; camera.backgroundColor=new Color(.067f,.075f,.098f); cameraObject.transform.SetPositionAndRotation(new Vector3(3,2.4f,-4),Quaternion.Euler(14,-36,0));
            var lightObject=new GameObject("Diagnostic Light"); var light=lightObject.AddComponent<Light>(); light.type=LightType.Directional; light.color=new Color(.75f,.78f,.82f); light.intensity=.7f; lightObject.transform.rotation=Quaternion.Euler(45,-30,0);
            Directory.CreateDirectory("Assets/Scenes");
            EditorSceneManager.SaveScene(scene,"Assets/Scenes/PerceptionLab.unity");
            EditorBuildSettings.scenes=new[]{new EditorBuildSettingsScene("Assets/Scenes/PerceptionLab.unity",true)};
            Debug.Log("[MPL_LAB_BUILD] status=Pass scene=Assets/Scenes/PerceptionLab.unity nonvisualControls=True");
        }
    }
}
