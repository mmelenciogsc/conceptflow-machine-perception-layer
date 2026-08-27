# SPDX-License-Identifier: MIT OR Apache-2.0
from pathlib import Path
from xml.etree import ElementTree


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
JNI_SOURCE = REPOSITORY_ROOT / "apps/android-host/src/main/cpp/qnn_jni.cpp"
MANIFEST = REPOSITORY_ROOT / "apps/android-host/src/main/AndroidManifest.xml"
PRIVATE_PROVISIONER = REPOSITORY_ROOT / "scripts/android-qnn-private-provision"
QNN_BUILDER = REPOSITORY_ROOT / "scripts/android-qnn-jni-build"
ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"


def test_public_cdsprpc_is_optional_and_declared_for_android_namespace_access() -> None:
    root = ElementTree.parse(MANIFEST).getroot()
    application = root.find("application")
    assert application is not None
    declarations = application.findall("uses-native-library")
    cdsprpc = [
        declaration
        for declaration in declarations
        if declaration.get(f"{{{ANDROID_NAMESPACE}}}name") == "libcdsprpc.so"
    ]
    assert len(cdsprpc) == 1
    assert cdsprpc[0].get(f"{{{ANDROID_NAMESPACE}}}required") == "false"


def test_public_cdsprpc_is_preloaded_by_soname_before_private_v79_stub() -> None:
    source = JNI_SOURCE.read_text(encoding="utf-8")
    public_load = source.index('session->platform_rpc_library, "libcdsprpc.so"')
    private_stub_load = source.index(
        'session->stub_library,\n                           joinPath(runtime_directory, "libQnnHtpV79Stub.so")'
    )
    assert public_load < private_stub_load
    assert 'RTLD_NOW | RTLD_GLOBAL, "platform public libcdsprpc.so"' in source
    assert 'joinPath(runtime_directory, "libcdsprpc.so")' not in source


def test_dynamic_libraries_use_bounded_diagnostics_and_raii_dependency_order() -> None:
    source = JNI_SOURCE.read_text(encoding="utf-8")
    assert "constexpr size_t kMaximumLoaderDetailBytes = 384;" in source
    assert "detail.resize(kMaximumLoaderDetailBytes - 3);" in source
    assert "~DynamicLibrary() { reset(); }" in source

    platform_member = source.index("DynamicLibrary platform_rpc_library;")
    stub_member = source.index("DynamicLibrary stub_library;")
    model_member = source.index("DynamicLibrary model_library;")
    assert platform_member < stub_member < model_member

    environment_restore = source.index("EnvironmentRestore adsp_restore{")
    session_construction = source.index("auto session = std::make_shared<Session>();")
    assert environment_restore < session_construction


def test_platform_cdsprpc_is_not_part_of_private_provisioning() -> None:
    provisioner = PRIVATE_PROVISIONER.read_text(encoding="utf-8")
    assert "libcdsprpc" not in provisioner


def test_named_qnn_outputs_are_reordered_to_the_stable_kotlin_abi() -> None:
    source = JNI_SOURCE.read_text(encoding="utf-8")
    assert "output_result_order" in source
    assert "matched[candidate]" in source
    assert "session->outputs[session->output_result_order[output_index]]" in source


def test_qnn_apk_is_preserved_outside_gradle_managed_outputs() -> None:
    builder = QNN_BUILDER.read_text(encoding="utf-8")
    assert 'qnn_directory="apps/android-host/build/qnn"' in builder
    assert 'qnn_apk="$qnn_directory/android-host-qnn-debug.apk"' in builder
    assert "build/outputs/apk/debug/android-host-qnn-debug.apk" not in builder
