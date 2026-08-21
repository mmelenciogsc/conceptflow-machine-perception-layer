# SPDX-License-Identifier: MIT OR Apache-2.0
import datetime

from google.protobuf import duration_pb2 as _duration_pb2
from google.protobuf import timestamp_pb2 as _timestamp_pb2
from google.protobuf.internal import containers as _containers
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class ImageEncoding(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    IMAGE_ENCODING_UNSPECIFIED: _ClassVar[ImageEncoding]
    IMAGE_ENCODING_RGB8: _ClassVar[ImageEncoding]
    IMAGE_ENCODING_RGBA8: _ClassVar[ImageEncoding]
    IMAGE_ENCODING_GRAY8: _ClassVar[ImageEncoding]
    IMAGE_ENCODING_JPEG: _ClassVar[ImageEncoding]
    IMAGE_ENCODING_PNG: _ClassVar[ImageEncoding]

class CueModality(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    CUE_MODALITY_UNSPECIFIED: _ClassVar[CueModality]
    CUE_MODALITY_EARCON: _ClassVar[CueModality]
    CUE_MODALITY_SPEECH: _ClassVar[CueModality]
    CUE_MODALITY_HAPTIC: _ClassVar[CueModality]

class CoordinateFrame(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    COORDINATE_FRAME_UNSPECIFIED: _ClassVar[CoordinateFrame]
    COORDINATE_FRAME_CAMERA_OPTICAL: _ClassVar[CoordinateFrame]
    COORDINATE_FRAME_HEAD: _ClassVar[CoordinateFrame]
    COORDINATE_FRAME_BODY: _ClassVar[CoordinateFrame]
    COORDINATE_FRAME_LOCAL_WORLD: _ClassVar[CoordinateFrame]

class ErrorCode(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    ERROR_CODE_UNSPECIFIED: _ClassVar[ErrorCode]
    ERROR_CODE_INVALID_ARGUMENT: _ClassVar[ErrorCode]
    ERROR_CODE_UNSUPPORTED_VERSION: _ClassVar[ErrorCode]
    ERROR_CODE_OVERSIZE: _ClassVar[ErrorCode]
    ERROR_CODE_OVERLOADED: _ClassVar[ErrorCode]
    ERROR_CODE_CANCELLED: _ClassVar[ErrorCode]
    ERROR_CODE_DEADLINE_EXCEEDED: _ClassVar[ErrorCode]
    ERROR_CODE_WORKER_UNAVAILABLE: _ClassVar[ErrorCode]
    ERROR_CODE_STALE: _ClassVar[ErrorCode]
    ERROR_CODE_INTERNAL: _ClassVar[ErrorCode]

class CueCategory(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    CUE_CATEGORY_UNSPECIFIED: _ClassVar[CueCategory]
    CUE_CATEGORY_OBSTACLE: _ClassVar[CueCategory]
    CUE_CATEGORY_NAVIGATION: _ClassVar[CueCategory]
    CUE_CATEGORY_TEXT: _ClassVar[CueCategory]
    CUE_CATEGORY_PERSON: _ClassVar[CueCategory]
    CUE_CATEGORY_OBJECT: _ClassVar[CueCategory]
    CUE_CATEGORY_SCENE: _ClassVar[CueCategory]
    CUE_CATEGORY_SYSTEM: _ClassVar[CueCategory]

class Direction(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    DIRECTION_UNSPECIFIED: _ClassVar[Direction]
    DIRECTION_AHEAD: _ClassVar[Direction]
    DIRECTION_LEFT: _ClassVar[Direction]
    DIRECTION_RIGHT: _ClassVar[Direction]
    DIRECTION_ABOVE: _ClassVar[Direction]
    DIRECTION_BELOW: _ClassVar[Direction]
    DIRECTION_BEHIND: _ClassVar[Direction]

class Urgency(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    URGENCY_UNSPECIFIED: _ClassVar[Urgency]
    URGENCY_LOW: _ClassVar[Urgency]
    URGENCY_NORMAL: _ClassVar[Urgency]
    URGENCY_HIGH: _ClassVar[Urgency]
    URGENCY_CRITICAL: _ClassVar[Urgency]

class HapticPattern(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    HAPTIC_PATTERN_UNSPECIFIED: _ClassVar[HapticPattern]
    HAPTIC_PATTERN_PULSE: _ClassVar[HapticPattern]
    HAPTIC_PATTERN_DOUBLE_PULSE: _ClassVar[HapticPattern]
    HAPTIC_PATTERN_RAMP: _ClassVar[HapticPattern]

class ServingStatus(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
    __slots__ = ()
    SERVING_STATUS_UNSPECIFIED: _ClassVar[ServingStatus]
    SERVING_STATUS_SERVING: _ClassVar[ServingStatus]
    SERVING_STATUS_DEGRADED: _ClassVar[ServingStatus]
    SERVING_STATUS_NOT_SERVING: _ClassVar[ServingStatus]
IMAGE_ENCODING_UNSPECIFIED: ImageEncoding
IMAGE_ENCODING_RGB8: ImageEncoding
IMAGE_ENCODING_RGBA8: ImageEncoding
IMAGE_ENCODING_GRAY8: ImageEncoding
IMAGE_ENCODING_JPEG: ImageEncoding
IMAGE_ENCODING_PNG: ImageEncoding
CUE_MODALITY_UNSPECIFIED: CueModality
CUE_MODALITY_EARCON: CueModality
CUE_MODALITY_SPEECH: CueModality
CUE_MODALITY_HAPTIC: CueModality
COORDINATE_FRAME_UNSPECIFIED: CoordinateFrame
COORDINATE_FRAME_CAMERA_OPTICAL: CoordinateFrame
COORDINATE_FRAME_HEAD: CoordinateFrame
COORDINATE_FRAME_BODY: CoordinateFrame
COORDINATE_FRAME_LOCAL_WORLD: CoordinateFrame
ERROR_CODE_UNSPECIFIED: ErrorCode
ERROR_CODE_INVALID_ARGUMENT: ErrorCode
ERROR_CODE_UNSUPPORTED_VERSION: ErrorCode
ERROR_CODE_OVERSIZE: ErrorCode
ERROR_CODE_OVERLOADED: ErrorCode
ERROR_CODE_CANCELLED: ErrorCode
ERROR_CODE_DEADLINE_EXCEEDED: ErrorCode
ERROR_CODE_WORKER_UNAVAILABLE: ErrorCode
ERROR_CODE_STALE: ErrorCode
ERROR_CODE_INTERNAL: ErrorCode
CUE_CATEGORY_UNSPECIFIED: CueCategory
CUE_CATEGORY_OBSTACLE: CueCategory
CUE_CATEGORY_NAVIGATION: CueCategory
CUE_CATEGORY_TEXT: CueCategory
CUE_CATEGORY_PERSON: CueCategory
CUE_CATEGORY_OBJECT: CueCategory
CUE_CATEGORY_SCENE: CueCategory
CUE_CATEGORY_SYSTEM: CueCategory
DIRECTION_UNSPECIFIED: Direction
DIRECTION_AHEAD: Direction
DIRECTION_LEFT: Direction
DIRECTION_RIGHT: Direction
DIRECTION_ABOVE: Direction
DIRECTION_BELOW: Direction
DIRECTION_BEHIND: Direction
URGENCY_UNSPECIFIED: Urgency
URGENCY_LOW: Urgency
URGENCY_NORMAL: Urgency
URGENCY_HIGH: Urgency
URGENCY_CRITICAL: Urgency
HAPTIC_PATTERN_UNSPECIFIED: HapticPattern
HAPTIC_PATTERN_PULSE: HapticPattern
HAPTIC_PATTERN_DOUBLE_PULSE: HapticPattern
HAPTIC_PATTERN_RAMP: HapticPattern
SERVING_STATUS_UNSPECIFIED: ServingStatus
SERVING_STATUS_SERVING: ServingStatus
SERVING_STATUS_DEGRADED: ServingStatus
SERVING_STATUS_NOT_SERVING: ServingStatus

class ProtocolVersion(_message.Message):
    __slots__ = ("major", "minor", "patch")
    MAJOR_FIELD_NUMBER: _ClassVar[int]
    MINOR_FIELD_NUMBER: _ClassVar[int]
    PATCH_FIELD_NUMBER: _ClassVar[int]
    major: int
    minor: int
    patch: int
    def __init__(self, major: _Optional[int] = ..., minor: _Optional[int] = ..., patch: _Optional[int] = ...) -> None: ...

class EphemeralIdentity(_message.Message):
    __slots__ = ("session_id", "nonce", "expires_at")
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    NONCE_FIELD_NUMBER: _ClassVar[int]
    EXPIRES_AT_FIELD_NUMBER: _ClassVar[int]
    session_id: str
    nonce: bytes
    expires_at: _timestamp_pb2.Timestamp
    def __init__(self, session_id: _Optional[str] = ..., nonce: _Optional[bytes] = ..., expires_at: _Optional[_Union[datetime.datetime, _timestamp_pb2.Timestamp, _Mapping]] = ...) -> None: ...

class CapabilitySet(_message.Message):
    __slots__ = ("image_encodings", "cue_modalities", "max_width", "max_height", "max_frame_bytes", "supports_cancellation", "supports_supersession", "supports_pose", "supports_intrinsics")
    IMAGE_ENCODINGS_FIELD_NUMBER: _ClassVar[int]
    CUE_MODALITIES_FIELD_NUMBER: _ClassVar[int]
    MAX_WIDTH_FIELD_NUMBER: _ClassVar[int]
    MAX_HEIGHT_FIELD_NUMBER: _ClassVar[int]
    MAX_FRAME_BYTES_FIELD_NUMBER: _ClassVar[int]
    SUPPORTS_CANCELLATION_FIELD_NUMBER: _ClassVar[int]
    SUPPORTS_SUPERSESSION_FIELD_NUMBER: _ClassVar[int]
    SUPPORTS_POSE_FIELD_NUMBER: _ClassVar[int]
    SUPPORTS_INTRINSICS_FIELD_NUMBER: _ClassVar[int]
    image_encodings: _containers.RepeatedScalarFieldContainer[ImageEncoding]
    cue_modalities: _containers.RepeatedScalarFieldContainer[CueModality]
    max_width: int
    max_height: int
    max_frame_bytes: int
    supports_cancellation: bool
    supports_supersession: bool
    supports_pose: bool
    supports_intrinsics: bool
    def __init__(self, image_encodings: _Optional[_Iterable[_Union[ImageEncoding, str]]] = ..., cue_modalities: _Optional[_Iterable[_Union[CueModality, str]]] = ..., max_width: _Optional[int] = ..., max_height: _Optional[int] = ..., max_frame_bytes: _Optional[int] = ..., supports_cancellation: bool = ..., supports_supersession: bool = ..., supports_pose: bool = ..., supports_intrinsics: bool = ...) -> None: ...

class QualityOfService(_message.Message):
    __slots__ = ("max_in_flight", "target_frames_per_second", "result_deadline", "allow_frame_drop", "max_cues_per_result")
    MAX_IN_FLIGHT_FIELD_NUMBER: _ClassVar[int]
    TARGET_FRAMES_PER_SECOND_FIELD_NUMBER: _ClassVar[int]
    RESULT_DEADLINE_FIELD_NUMBER: _ClassVar[int]
    ALLOW_FRAME_DROP_FIELD_NUMBER: _ClassVar[int]
    MAX_CUES_PER_RESULT_FIELD_NUMBER: _ClassVar[int]
    max_in_flight: int
    target_frames_per_second: int
    result_deadline: _duration_pb2.Duration
    allow_frame_drop: bool
    max_cues_per_result: int
    def __init__(self, max_in_flight: _Optional[int] = ..., target_frames_per_second: _Optional[int] = ..., result_deadline: _Optional[_Union[datetime.timedelta, _duration_pb2.Duration, _Mapping]] = ..., allow_frame_drop: bool = ..., max_cues_per_result: _Optional[int] = ...) -> None: ...

class NegotiateRequest(_message.Message):
    __slots__ = ("client_instance_id", "supported_versions", "identity", "capabilities", "requested_qos")
    CLIENT_INSTANCE_ID_FIELD_NUMBER: _ClassVar[int]
    SUPPORTED_VERSIONS_FIELD_NUMBER: _ClassVar[int]
    IDENTITY_FIELD_NUMBER: _ClassVar[int]
    CAPABILITIES_FIELD_NUMBER: _ClassVar[int]
    REQUESTED_QOS_FIELD_NUMBER: _ClassVar[int]
    client_instance_id: str
    supported_versions: _containers.RepeatedCompositeFieldContainer[ProtocolVersion]
    identity: EphemeralIdentity
    capabilities: CapabilitySet
    requested_qos: QualityOfService
    def __init__(self, client_instance_id: _Optional[str] = ..., supported_versions: _Optional[_Iterable[_Union[ProtocolVersion, _Mapping]]] = ..., identity: _Optional[_Union[EphemeralIdentity, _Mapping]] = ..., capabilities: _Optional[_Union[CapabilitySet, _Mapping]] = ..., requested_qos: _Optional[_Union[QualityOfService, _Mapping]] = ...) -> None: ...

class NegotiateResponse(_message.Message):
    __slots__ = ("selected_version", "identity", "capabilities", "accepted_qos", "error")
    SELECTED_VERSION_FIELD_NUMBER: _ClassVar[int]
    IDENTITY_FIELD_NUMBER: _ClassVar[int]
    CAPABILITIES_FIELD_NUMBER: _ClassVar[int]
    ACCEPTED_QOS_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    selected_version: ProtocolVersion
    identity: EphemeralIdentity
    capabilities: CapabilitySet
    accepted_qos: QualityOfService
    error: ErrorStatus
    def __init__(self, selected_version: _Optional[_Union[ProtocolVersion, _Mapping]] = ..., identity: _Optional[_Union[EphemeralIdentity, _Mapping]] = ..., capabilities: _Optional[_Union[CapabilitySet, _Mapping]] = ..., accepted_qos: _Optional[_Union[QualityOfService, _Mapping]] = ..., error: _Optional[_Union[ErrorStatus, _Mapping]] = ...) -> None: ...

class ImageDescriptor(_message.Message):
    __slots__ = ("width", "height", "row_stride_bytes", "encoding", "media_type", "payload_bytes", "sha256")
    WIDTH_FIELD_NUMBER: _ClassVar[int]
    HEIGHT_FIELD_NUMBER: _ClassVar[int]
    ROW_STRIDE_BYTES_FIELD_NUMBER: _ClassVar[int]
    ENCODING_FIELD_NUMBER: _ClassVar[int]
    MEDIA_TYPE_FIELD_NUMBER: _ClassVar[int]
    PAYLOAD_BYTES_FIELD_NUMBER: _ClassVar[int]
    SHA256_FIELD_NUMBER: _ClassVar[int]
    width: int
    height: int
    row_stride_bytes: int
    encoding: ImageEncoding
    media_type: str
    payload_bytes: int
    sha256: bytes
    def __init__(self, width: _Optional[int] = ..., height: _Optional[int] = ..., row_stride_bytes: _Optional[int] = ..., encoding: _Optional[_Union[ImageEncoding, str]] = ..., media_type: _Optional[str] = ..., payload_bytes: _Optional[int] = ..., sha256: _Optional[bytes] = ...) -> None: ...

class CameraIntrinsics(_message.Message):
    __slots__ = ("focal_x_pixels", "focal_y_pixels", "principal_x_pixels", "principal_y_pixels", "distortion_coefficients", "calibrated_width", "calibrated_height")
    FOCAL_X_PIXELS_FIELD_NUMBER: _ClassVar[int]
    FOCAL_Y_PIXELS_FIELD_NUMBER: _ClassVar[int]
    PRINCIPAL_X_PIXELS_FIELD_NUMBER: _ClassVar[int]
    PRINCIPAL_Y_PIXELS_FIELD_NUMBER: _ClassVar[int]
    DISTORTION_COEFFICIENTS_FIELD_NUMBER: _ClassVar[int]
    CALIBRATED_WIDTH_FIELD_NUMBER: _ClassVar[int]
    CALIBRATED_HEIGHT_FIELD_NUMBER: _ClassVar[int]
    focal_x_pixels: float
    focal_y_pixels: float
    principal_x_pixels: float
    principal_y_pixels: float
    distortion_coefficients: _containers.RepeatedScalarFieldContainer[float]
    calibrated_width: int
    calibrated_height: int
    def __init__(self, focal_x_pixels: _Optional[float] = ..., focal_y_pixels: _Optional[float] = ..., principal_x_pixels: _Optional[float] = ..., principal_y_pixels: _Optional[float] = ..., distortion_coefficients: _Optional[_Iterable[float]] = ..., calibrated_width: _Optional[int] = ..., calibrated_height: _Optional[int] = ...) -> None: ...

class Vector3(_message.Message):
    __slots__ = ("x", "y", "z")
    X_FIELD_NUMBER: _ClassVar[int]
    Y_FIELD_NUMBER: _ClassVar[int]
    Z_FIELD_NUMBER: _ClassVar[int]
    x: float
    y: float
    z: float
    def __init__(self, x: _Optional[float] = ..., y: _Optional[float] = ..., z: _Optional[float] = ...) -> None: ...

class Quaternion(_message.Message):
    __slots__ = ("x", "y", "z", "w")
    X_FIELD_NUMBER: _ClassVar[int]
    Y_FIELD_NUMBER: _ClassVar[int]
    Z_FIELD_NUMBER: _ClassVar[int]
    W_FIELD_NUMBER: _ClassVar[int]
    x: float
    y: float
    z: float
    w: float
    def __init__(self, x: _Optional[float] = ..., y: _Optional[float] = ..., z: _Optional[float] = ..., w: _Optional[float] = ...) -> None: ...

class Pose(_message.Message):
    __slots__ = ("reference_frame", "translation_meters", "rotation", "monotonic_timestamp_ns")
    REFERENCE_FRAME_FIELD_NUMBER: _ClassVar[int]
    TRANSLATION_METERS_FIELD_NUMBER: _ClassVar[int]
    ROTATION_FIELD_NUMBER: _ClassVar[int]
    MONOTONIC_TIMESTAMP_NS_FIELD_NUMBER: _ClassVar[int]
    reference_frame: CoordinateFrame
    translation_meters: Vector3
    rotation: Quaternion
    monotonic_timestamp_ns: int
    def __init__(self, reference_frame: _Optional[_Union[CoordinateFrame, str]] = ..., translation_meters: _Optional[_Union[Vector3, _Mapping]] = ..., rotation: _Optional[_Union[Quaternion, _Mapping]] = ..., monotonic_timestamp_ns: _Optional[int] = ...) -> None: ...

class FramePayload(_message.Message):
    __slots__ = ("request_id", "session_id", "stream_id", "frame_id", "capture_monotonic_timestamp_ns", "capture_wall_time", "image", "intrinsics", "pose", "frame_data", "processing_deadline", "synthetic")
    REQUEST_ID_FIELD_NUMBER: _ClassVar[int]
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    STREAM_ID_FIELD_NUMBER: _ClassVar[int]
    FRAME_ID_FIELD_NUMBER: _ClassVar[int]
    CAPTURE_MONOTONIC_TIMESTAMP_NS_FIELD_NUMBER: _ClassVar[int]
    CAPTURE_WALL_TIME_FIELD_NUMBER: _ClassVar[int]
    IMAGE_FIELD_NUMBER: _ClassVar[int]
    INTRINSICS_FIELD_NUMBER: _ClassVar[int]
    POSE_FIELD_NUMBER: _ClassVar[int]
    FRAME_DATA_FIELD_NUMBER: _ClassVar[int]
    PROCESSING_DEADLINE_FIELD_NUMBER: _ClassVar[int]
    SYNTHETIC_FIELD_NUMBER: _ClassVar[int]
    request_id: str
    session_id: str
    stream_id: str
    frame_id: int
    capture_monotonic_timestamp_ns: int
    capture_wall_time: _timestamp_pb2.Timestamp
    image: ImageDescriptor
    intrinsics: CameraIntrinsics
    pose: Pose
    frame_data: bytes
    processing_deadline: _duration_pb2.Duration
    synthetic: bool
    def __init__(self, request_id: _Optional[str] = ..., session_id: _Optional[str] = ..., stream_id: _Optional[str] = ..., frame_id: _Optional[int] = ..., capture_monotonic_timestamp_ns: _Optional[int] = ..., capture_wall_time: _Optional[_Union[datetime.datetime, _timestamp_pb2.Timestamp, _Mapping]] = ..., image: _Optional[_Union[ImageDescriptor, _Mapping]] = ..., intrinsics: _Optional[_Union[CameraIntrinsics, _Mapping]] = ..., pose: _Optional[_Union[Pose, _Mapping]] = ..., frame_data: _Optional[bytes] = ..., processing_deadline: _Optional[_Union[datetime.timedelta, _duration_pb2.Duration, _Mapping]] = ..., synthetic: bool = ...) -> None: ...

class ErrorStatus(_message.Message):
    __slots__ = ("code", "message", "retryable", "retry_after_ms", "correlation_id")
    CODE_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    RETRYABLE_FIELD_NUMBER: _ClassVar[int]
    RETRY_AFTER_MS_FIELD_NUMBER: _ClassVar[int]
    CORRELATION_ID_FIELD_NUMBER: _ClassVar[int]
    code: ErrorCode
    message: str
    retryable: bool
    retry_after_ms: int
    correlation_id: str
    def __init__(self, code: _Optional[_Union[ErrorCode, str]] = ..., message: _Optional[str] = ..., retryable: bool = ..., retry_after_ms: _Optional[int] = ..., correlation_id: _Optional[str] = ...) -> None: ...

class Provenance(_message.Message):
    __slots__ = ("component", "component_version", "worker_id", "model_id", "model_version", "artifact_digest", "processing_started_monotonic_ns", "processing_finished_monotonic_ns", "source_result_ids", "synthetic")
    COMPONENT_FIELD_NUMBER: _ClassVar[int]
    COMPONENT_VERSION_FIELD_NUMBER: _ClassVar[int]
    WORKER_ID_FIELD_NUMBER: _ClassVar[int]
    MODEL_ID_FIELD_NUMBER: _ClassVar[int]
    MODEL_VERSION_FIELD_NUMBER: _ClassVar[int]
    ARTIFACT_DIGEST_FIELD_NUMBER: _ClassVar[int]
    PROCESSING_STARTED_MONOTONIC_NS_FIELD_NUMBER: _ClassVar[int]
    PROCESSING_FINISHED_MONOTONIC_NS_FIELD_NUMBER: _ClassVar[int]
    SOURCE_RESULT_IDS_FIELD_NUMBER: _ClassVar[int]
    SYNTHETIC_FIELD_NUMBER: _ClassVar[int]
    component: str
    component_version: str
    worker_id: str
    model_id: str
    model_version: str
    artifact_digest: str
    processing_started_monotonic_ns: int
    processing_finished_monotonic_ns: int
    source_result_ids: _containers.RepeatedScalarFieldContainer[str]
    synthetic: bool
    def __init__(self, component: _Optional[str] = ..., component_version: _Optional[str] = ..., worker_id: _Optional[str] = ..., model_id: _Optional[str] = ..., model_version: _Optional[str] = ..., artifact_digest: _Optional[str] = ..., processing_started_monotonic_ns: _Optional[int] = ..., processing_finished_monotonic_ns: _Optional[int] = ..., source_result_ids: _Optional[_Iterable[str]] = ..., synthetic: bool = ...) -> None: ...

class BoundingBox(_message.Message):
    __slots__ = ("left", "top", "right", "bottom")
    LEFT_FIELD_NUMBER: _ClassVar[int]
    TOP_FIELD_NUMBER: _ClassVar[int]
    RIGHT_FIELD_NUMBER: _ClassVar[int]
    BOTTOM_FIELD_NUMBER: _ClassVar[int]
    left: float
    top: float
    right: float
    bottom: float
    def __init__(self, left: _Optional[float] = ..., top: _Optional[float] = ..., right: _Optional[float] = ..., bottom: _Optional[float] = ...) -> None: ...

class PerceptionObservation(_message.Message):
    __slots__ = ("observation_id", "category", "description", "confidence", "normalized_bounds", "coordinate_frame", "azimuth_degrees", "elevation_degrees", "distance_meters", "provenance")
    OBSERVATION_ID_FIELD_NUMBER: _ClassVar[int]
    CATEGORY_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    CONFIDENCE_FIELD_NUMBER: _ClassVar[int]
    NORMALIZED_BOUNDS_FIELD_NUMBER: _ClassVar[int]
    COORDINATE_FRAME_FIELD_NUMBER: _ClassVar[int]
    AZIMUTH_DEGREES_FIELD_NUMBER: _ClassVar[int]
    ELEVATION_DEGREES_FIELD_NUMBER: _ClassVar[int]
    DISTANCE_METERS_FIELD_NUMBER: _ClassVar[int]
    PROVENANCE_FIELD_NUMBER: _ClassVar[int]
    observation_id: str
    category: str
    description: str
    confidence: float
    normalized_bounds: BoundingBox
    coordinate_frame: CoordinateFrame
    azimuth_degrees: float
    elevation_degrees: float
    distance_meters: float
    provenance: Provenance
    def __init__(self, observation_id: _Optional[str] = ..., category: _Optional[str] = ..., description: _Optional[str] = ..., confidence: _Optional[float] = ..., normalized_bounds: _Optional[_Union[BoundingBox, _Mapping]] = ..., coordinate_frame: _Optional[_Union[CoordinateFrame, str]] = ..., azimuth_degrees: _Optional[float] = ..., elevation_degrees: _Optional[float] = ..., distance_meters: _Optional[float] = ..., provenance: _Optional[_Union[Provenance, _Mapping]] = ...) -> None: ...

class Earcon(_message.Message):
    __slots__ = ("earcon_id", "gain", "pitch", "spatialized")
    EARCON_ID_FIELD_NUMBER: _ClassVar[int]
    GAIN_FIELD_NUMBER: _ClassVar[int]
    PITCH_FIELD_NUMBER: _ClassVar[int]
    SPATIALIZED_FIELD_NUMBER: _ClassVar[int]
    earcon_id: str
    gain: float
    pitch: float
    spatialized: bool
    def __init__(self, earcon_id: _Optional[str] = ..., gain: _Optional[float] = ..., pitch: _Optional[float] = ..., spatialized: bool = ...) -> None: ...

class Speech(_message.Message):
    __slots__ = ("text", "language_tag", "interrupt")
    TEXT_FIELD_NUMBER: _ClassVar[int]
    LANGUAGE_TAG_FIELD_NUMBER: _ClassVar[int]
    INTERRUPT_FIELD_NUMBER: _ClassVar[int]
    text: str
    language_tag: str
    interrupt: bool
    def __init__(self, text: _Optional[str] = ..., language_tag: _Optional[str] = ..., interrupt: bool = ...) -> None: ...

class Haptic(_message.Message):
    __slots__ = ("pattern", "intensity", "duration_ms")
    PATTERN_FIELD_NUMBER: _ClassVar[int]
    INTENSITY_FIELD_NUMBER: _ClassVar[int]
    DURATION_MS_FIELD_NUMBER: _ClassVar[int]
    pattern: HapticPattern
    intensity: float
    duration_ms: int
    def __init__(self, pattern: _Optional[_Union[HapticPattern, str]] = ..., intensity: _Optional[float] = ..., duration_ms: _Optional[int] = ...) -> None: ...

class CueCancellation(_message.Message):
    __slots__ = ("cue_ids", "reason")
    CUE_IDS_FIELD_NUMBER: _ClassVar[int]
    REASON_FIELD_NUMBER: _ClassVar[int]
    cue_ids: _containers.RepeatedScalarFieldContainer[str]
    reason: str
    def __init__(self, cue_ids: _Optional[_Iterable[str]] = ..., reason: _Optional[str] = ...) -> None: ...

class CueSupersession(_message.Message):
    __slots__ = ("cue_ids", "reason")
    CUE_IDS_FIELD_NUMBER: _ClassVar[int]
    REASON_FIELD_NUMBER: _ClassVar[int]
    cue_ids: _containers.RepeatedScalarFieldContainer[str]
    reason: str
    def __init__(self, cue_ids: _Optional[_Iterable[str]] = ..., reason: _Optional[str] = ...) -> None: ...

class PerceptionCue(_message.Message):
    __slots__ = ("cue_id", "frame_id", "created_monotonic_timestamp_ns", "ttl_ms", "category", "description", "confidence", "priority", "coordinate_frame", "azimuth_degrees", "elevation_degrees", "distance_meters", "direction", "urgency", "earcon", "speech", "haptic", "cancel", "supersede", "provenance")
    CUE_ID_FIELD_NUMBER: _ClassVar[int]
    FRAME_ID_FIELD_NUMBER: _ClassVar[int]
    CREATED_MONOTONIC_TIMESTAMP_NS_FIELD_NUMBER: _ClassVar[int]
    TTL_MS_FIELD_NUMBER: _ClassVar[int]
    CATEGORY_FIELD_NUMBER: _ClassVar[int]
    DESCRIPTION_FIELD_NUMBER: _ClassVar[int]
    CONFIDENCE_FIELD_NUMBER: _ClassVar[int]
    PRIORITY_FIELD_NUMBER: _ClassVar[int]
    COORDINATE_FRAME_FIELD_NUMBER: _ClassVar[int]
    AZIMUTH_DEGREES_FIELD_NUMBER: _ClassVar[int]
    ELEVATION_DEGREES_FIELD_NUMBER: _ClassVar[int]
    DISTANCE_METERS_FIELD_NUMBER: _ClassVar[int]
    DIRECTION_FIELD_NUMBER: _ClassVar[int]
    URGENCY_FIELD_NUMBER: _ClassVar[int]
    EARCON_FIELD_NUMBER: _ClassVar[int]
    SPEECH_FIELD_NUMBER: _ClassVar[int]
    HAPTIC_FIELD_NUMBER: _ClassVar[int]
    CANCEL_FIELD_NUMBER: _ClassVar[int]
    SUPERSEDE_FIELD_NUMBER: _ClassVar[int]
    PROVENANCE_FIELD_NUMBER: _ClassVar[int]
    cue_id: str
    frame_id: int
    created_monotonic_timestamp_ns: int
    ttl_ms: int
    category: CueCategory
    description: str
    confidence: float
    priority: int
    coordinate_frame: CoordinateFrame
    azimuth_degrees: float
    elevation_degrees: float
    distance_meters: float
    direction: Direction
    urgency: Urgency
    earcon: Earcon
    speech: Speech
    haptic: Haptic
    cancel: CueCancellation
    supersede: CueSupersession
    provenance: Provenance
    def __init__(self, cue_id: _Optional[str] = ..., frame_id: _Optional[int] = ..., created_monotonic_timestamp_ns: _Optional[int] = ..., ttl_ms: _Optional[int] = ..., category: _Optional[_Union[CueCategory, str]] = ..., description: _Optional[str] = ..., confidence: _Optional[float] = ..., priority: _Optional[int] = ..., coordinate_frame: _Optional[_Union[CoordinateFrame, str]] = ..., azimuth_degrees: _Optional[float] = ..., elevation_degrees: _Optional[float] = ..., distance_meters: _Optional[float] = ..., direction: _Optional[_Union[Direction, str]] = ..., urgency: _Optional[_Union[Urgency, str]] = ..., earcon: _Optional[_Union[Earcon, _Mapping]] = ..., speech: _Optional[_Union[Speech, _Mapping]] = ..., haptic: _Optional[_Union[Haptic, _Mapping]] = ..., cancel: _Optional[_Union[CueCancellation, _Mapping]] = ..., supersede: _Optional[_Union[CueSupersession, _Mapping]] = ..., provenance: _Optional[_Union[Provenance, _Mapping]] = ...) -> None: ...

class PerceptionResult(_message.Message):
    __slots__ = ("result_id", "request_id", "session_id", "stream_id", "frame_id", "capture_monotonic_timestamp_ns", "completed_monotonic_timestamp_ns", "observations", "cues", "error", "provenance")
    RESULT_ID_FIELD_NUMBER: _ClassVar[int]
    REQUEST_ID_FIELD_NUMBER: _ClassVar[int]
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    STREAM_ID_FIELD_NUMBER: _ClassVar[int]
    FRAME_ID_FIELD_NUMBER: _ClassVar[int]
    CAPTURE_MONOTONIC_TIMESTAMP_NS_FIELD_NUMBER: _ClassVar[int]
    COMPLETED_MONOTONIC_TIMESTAMP_NS_FIELD_NUMBER: _ClassVar[int]
    OBSERVATIONS_FIELD_NUMBER: _ClassVar[int]
    CUES_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    PROVENANCE_FIELD_NUMBER: _ClassVar[int]
    result_id: str
    request_id: str
    session_id: str
    stream_id: str
    frame_id: int
    capture_monotonic_timestamp_ns: int
    completed_monotonic_timestamp_ns: int
    observations: _containers.RepeatedCompositeFieldContainer[PerceptionObservation]
    cues: _containers.RepeatedCompositeFieldContainer[PerceptionCue]
    error: ErrorStatus
    provenance: Provenance
    def __init__(self, result_id: _Optional[str] = ..., request_id: _Optional[str] = ..., session_id: _Optional[str] = ..., stream_id: _Optional[str] = ..., frame_id: _Optional[int] = ..., capture_monotonic_timestamp_ns: _Optional[int] = ..., completed_monotonic_timestamp_ns: _Optional[int] = ..., observations: _Optional[_Iterable[_Union[PerceptionObservation, _Mapping]]] = ..., cues: _Optional[_Iterable[_Union[PerceptionCue, _Mapping]]] = ..., error: _Optional[_Union[ErrorStatus, _Mapping]] = ..., provenance: _Optional[_Union[Provenance, _Mapping]] = ...) -> None: ...

class HealthRequest(_message.Message):
    __slots__ = ("include_workers",)
    INCLUDE_WORKERS_FIELD_NUMBER: _ClassVar[int]
    include_workers: bool
    def __init__(self, include_workers: bool = ...) -> None: ...

class WorkerHealth(_message.Message):
    __slots__ = ("worker_id", "device", "healthy", "consecutive_failures", "queue_depth")
    WORKER_ID_FIELD_NUMBER: _ClassVar[int]
    DEVICE_FIELD_NUMBER: _ClassVar[int]
    HEALTHY_FIELD_NUMBER: _ClassVar[int]
    CONSECUTIVE_FAILURES_FIELD_NUMBER: _ClassVar[int]
    QUEUE_DEPTH_FIELD_NUMBER: _ClassVar[int]
    worker_id: str
    device: str
    healthy: bool
    consecutive_failures: int
    queue_depth: int
    def __init__(self, worker_id: _Optional[str] = ..., device: _Optional[str] = ..., healthy: bool = ..., consecutive_failures: _Optional[int] = ..., queue_depth: _Optional[int] = ...) -> None: ...

class HealthResponse(_message.Message):
    __slots__ = ("status", "protocol_version", "workers", "queue_depth", "queue_capacity")
    STATUS_FIELD_NUMBER: _ClassVar[int]
    PROTOCOL_VERSION_FIELD_NUMBER: _ClassVar[int]
    WORKERS_FIELD_NUMBER: _ClassVar[int]
    QUEUE_DEPTH_FIELD_NUMBER: _ClassVar[int]
    QUEUE_CAPACITY_FIELD_NUMBER: _ClassVar[int]
    status: ServingStatus
    protocol_version: ProtocolVersion
    workers: _containers.RepeatedCompositeFieldContainer[WorkerHealth]
    queue_depth: int
    queue_capacity: int
    def __init__(self, status: _Optional[_Union[ServingStatus, str]] = ..., protocol_version: _Optional[_Union[ProtocolVersion, _Mapping]] = ..., workers: _Optional[_Iterable[_Union[WorkerHealth, _Mapping]]] = ..., queue_depth: _Optional[int] = ..., queue_capacity: _Optional[int] = ...) -> None: ...
