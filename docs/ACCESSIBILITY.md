<!-- SPDX-License-Identifier: MIT OR Apache-2.0 -->
# Accessibility

Accessibility is release-blocking for an MPL product/device release. A build that passes automated tests
but prevents a blind or low-vision user from discovering capture state,
stopping work, understanding an error, or controlling cue volume and frequency
is not ready to ship.

The repository provides implementation semantics and manual procedures. It
does not claim completed usability research, accessibility certification, or
manual validation with TalkBack, JAWS, or NVDA.

## Perception laboratory and training

The Unity visualization is optional developer diagnostics. Keyboard commands
and every live metric are documented in [the lab guide](UNITY_FMOD_LAB.md), and
`E` exports a plain UTF-8 status report. Where Unity does not expose reliable
screenreader semantics, use the fully textual equivalents:

```bash
./scripts/perception-demo
./scripts/perception-training --list
./scripts/perception-training --exercise above-vs-below --answer above
```

Training contains eight deterministic, gradually harder exercises with explicit
choices and objective scoring. Preferences use validated, local-only JSON and
can be written atomically with mode `0600` through the Python API. Live mobility
is not gamified; scoring belongs only to controlled training.

Audio output has bounded anchor/field gains and voice count. Haptics are brief,
capability-detected, and explicitly nonspatial on the current default-actuator
Android adapter. No visual overlay, color, or animation is required to inspect
operational state.

## Product principles

- Every state and error has text. Sound, haptics, position, animation, and color
  may reinforce that text but cannot replace it.
- Capture starts off. Its current state and stop control must remain
  discoverable through the platform accessibility API.
- Focus order follows task order. An asynchronous error returns focus to the
  initiating control when practical.
- Cues are bounded by freshness, capacity, priority, and deduplication so the
  assistive channel does not become an attention-denial channel.
- Speech must coexist with the user’s screen reader. The Android host’s
  `AccessibilityAwareSpeechFeedback` suppresses its own TTS while an Android
  accessibility service is enabled.
- “Near-real-time” and “zero-touch” are interaction goals only. They do not mean
  zero physical latency, complete perception, or any safety guarantee.

## Implemented semantics

### Android host

`apps/android-host/src/main/res/layout/activity_main.xml` uses stock `TextView`
and `Button` controls, marks the title as a heading, exposes session status as a
polite live region, sets 48 dp minimum button heights, and defines directional
focus between Connect, Process, environment mode, diagnostics, Cancel, and
Disconnect. Matching keyboard shortcuts are handled in
`MainActivity.onKeyUp`: `C`, `P`, `V`, `A`, `I`, `O`, `E`, `X`, and `D`.

Automatic, manual-indoor, and manual-outdoor buttons expose selected state with
`ViewCompat.setStateDescription`. The environment status is a polite live
region and explains whether the app is awaiting camera evidence, taking a
bounded optional GNSS sample, or applying a manual profile. Location permission
is requested only after explicit activation of Automatic mode; denial leaves
camera classification and both manual choices available.

`MainActivity.announceState` updates visible status before optional speech. A
separate polite `cue_status` live region receives bounded equivalent cue text
when negotiated speech is suppressed or unavailable.
`AccessibilityAwareSpeechFeedback.speak` returns a distinct
`SUPPRESSED_FOR_ACCESSIBILITY` outcome when an accessibility service is active,
avoiding competing application speech.

### Rokid client

Rokid AI Glasses Style is a non-display device. The client therefore has no
launcher activity, layout, visual controls, focus order, or touchpad workflow.
Its current development interface is explicit authorized-ADB control of a
nonvisual activity and private bound service. This is inspectable engineering
access, not an acceptable end-user control surface.

`InspectableCueRenderer` records disposition, frame, stereo balance, and
haptic use. It rejects invalid, stale, duplicate, and older-frame cues. These
properties make behavior auditable but do not establish that a particular
glasses speaker, haptic path, or spatial percept is usable.

A product path must put capture state, stop, errors, cue verbosity, and consent
in the TalkBack-accessible phone host and reinforce essential state through
distinctive nonvisual feedback on the glasses. Hands-free control remains
unverified until physical button or voice behavior is mapped without a vendor
SDK and tested with blind users.

### Windows relay

`apps/desktop-relay/src/ConceptFlow.Mpl.DesktopRelay.Wpf/MainWindow.xaml` uses
stock WPF controls with labels, access keys, deterministic `TabIndex` values,
UI Automation names/help, polite session/status regions, and an assertive error
region. Text always accompanies the optional system sound.

The current WPF shell starts capture off, requires one-shot approval, and
consumes that approval after submission. `MainWindow.ShowError` restores focus
to the initiating element. The cross-platform headless demo prints the same
state and synthetic cue information as plain text.

## TalkBack procedure

Run on the Android host. The non-display glasses have no TalkBack UI; validate
their audio/haptic behavior separately. Record OS, device, screen reader, input
method, app revision, and transport mode.

1. Enable TalkBack before launch. Confirm the app title and initial capture or
   session state are announced without starting capture, connecting, or playing
   a cue.
2. Explore controls by swipe and external keyboard. Confirm name, role, enabled
   state, and task order. Verify reverse keyboard traversal where supported.
3. On the host, activate Connect, Process synthetic frame, Cancel, and
   Disconnect. Confirm each state is visible and announced once, and that app
   TTS does not talk over TalkBack.
4. Activate Automatic, Manual indoor, and Manual outdoor. Confirm each selected
   state is announced and exposed to accessibility services. Deny the optional
   location request and confirm automatic camera classification and manual
   selection remain available. Run the synthetic environment diagnostic and
   confirm it explicitly identifies test data.
5. From the phone host, deny camera permission and confirm the denial is
   announced and glasses capture remains stopped. Grant permission, start and
   stop capture, disconnect, and confirm state remains available nonvisually.
6. Trigger left and right synthetic cues. Confirm equivalent text remains
   available and that stereo/haptic feedback is distinguishable without being
   painful, startling, or mistaken for a safety instruction.
7. Cause a malformed, stale, timeout, transport-loss, and overload condition in
   a synthetic environment. Confirm the user can identify the failure and
   return to a safe stopped state.
8. On the phone, increase display/font size, enable high contrast or color
   correction, and test supported orientations. Confirm controls remain usable.

Status: not yet manually validated on the attached Rokid consumer device or a
Poco host with TalkBack. JVM tests and APK builds do not satisfy this procedure.

## Keyboard procedure

### Android or attached-key input

Verify `Tab`/`Shift+Tab`, D-pad navigation where available, activation with
Enter/Space, and the documented letter keys. Focus must remain visible; a
disabled action must be reported as disabled; Cancel/Disconnect must remain
reachable while work is active.

### Windows

1. Launch the WPF app without a mouse. Initial focus should be Transport mode.
2. Traverse forward and backward through transport, endpoint, insecure-loopback
   option, Connect, Stop, capture type, approval, Submit, history, and sound.
3. Verify access keys identify and focus their target controls.
4. Connect in deterministic in-process mode, approve one synthetic submission,
   submit, and confirm approval is consumed and Submit disables again.
5. Enter malformed or disallowed endpoint text. Activate Connect, read the
   error, and verify focus returns to the initiating control.
6. Stop during an operation and confirm the session identifier clears, capture
   reads Off, and no retry or submission continues.

Status: keyboard semantics are implemented and build-tested, but the full
manual Windows keyboard procedure has not been run on Windows.

## JAWS and NVDA procedure

Run the following matrix independently with current supported JAWS and NVDA on
Windows 11. Use the synthetic in-process mode; no real screen content is needed.

1. Start the screen reader before the app. Confirm window name, capture-off
   statement, first focused control, and no unsolicited operation.
2. Execute the keyboard procedure above. Verify labels, access keys, roles,
   states, and live-region changes through speech and braille output where
   available.
3. Confirm the error region interrupts appropriately while normal status is
   polite. Repeated state changes must not create a speech loop.
4. Inspect the UI with Accessibility Insights or Inspect. Verify Automation
   Name, HelpText, control type, label relationships, focusability, enabled
   state, and live settings.
5. Test 200% scaling, Windows high contrast, large text, and system-sound off.
   All content and actions must remain available through text and keyboard.
6. Record defects by exact control and state transition, using synthetic data.

Status: no manual Windows, JAWS, or NVDA test has been completed. The verified
Ubuntu .NET build and 156 xUnit tests do not exercise Windows UI Automation or
screen-reader output.

## Cue acceptance criteria

Before a physical release, representative blind and low-vision evaluation must
confirm that cue categories are learnable; directional sound is unambiguous;
haptics do not mask other signals; speech respects interruption and verbosity;
deduplication reduces overload; urgent cues are not overstated; stale cues are
not perceived; all feedback has an accessible state/history equivalent; and
the user can immediately stop capture and feedback.

Any failure to announce capture, consent, transport failure, or cancellation;
any unreachable stop control; any stale or repeated cue that could mislead; or
any dependence on color/sound alone blocks release.

See [ANDROID_HOST.md](ANDROID_HOST.md), [ROKID_INTEGRATION.md](ROKID_INTEGRATION.md),
[WINDOWS_RELAY.md](WINDOWS_RELAY.md), and [THREAT_MODEL.md](THREAT_MODEL.md).
