// SPDX-License-Identifier: MIT OR Apache-2.0

using System.Media;
using System.Windows;
using System.Windows.Input;
using ConceptFlow.Mpl.DesktopRelay.Core;
using ConceptFlow.Mpl.V1;

namespace ConceptFlow.Mpl.DesktopRelay.Wpf;

public partial class MainWindow : Window, IRelayStatusSink
{
    private RelaySession? _session;
    private CancellationTokenSource? _operationCancellation;
    private readonly BoundedTextHistory _history = new();
    private readonly WpfSpeechCueOutputAdapter _cueOutput;
    private bool _busy;
    private ulong _frameId;

    public MainWindow()
    {
        InitializeComponent();
        _cueOutput = new WpfSpeechCueOutputAdapter(CueOutputText);
        Loaded += (_, _) => TransportMode.Focus();
    }

    public void Report(SafeStatus status)
    {
        Dispatcher.InvokeAsync(() =>
        {
            StatusText.Text = $"Status: {status.Message}";
            AppendHistory($"{status.Code}: {status.Message}");
        });
    }

    protected override async void OnClosed(EventArgs e)
    {
        _operationCancellation?.Cancel();
        _operationCancellation?.Dispose();
        if (_session is not null)
        {
            await _session.DisposeAsync();
        }

        base.OnClosed(e);
    }

    private async void ConnectButton_Click(object sender, RoutedEventArgs e)
    {
        var restoreFocus = Keyboard.FocusedElement as UIElement;
        ClearError();
        SetBusy(true);
        try
        {
            if (_session is not null)
            {
                await _session.DisposeAsync();
            }

            _operationCancellation?.Dispose();
            _operationCancellation = new CancellationTokenSource();
            var options = new RelayOptions
            {
                AllowInsecureLoopbackForDevelopment = AllowInsecureLoopbackCheckBox.IsChecked == true,
            };
            IRelayTransportFactory factory = TransportMode.SelectedIndex == 0
                ? new InProcessRelayTransportFactory()
                : new GrpcRelayTransportFactory();
            _session = new RelaySession(factory, options, statusSink: this, cueOutput: _cueOutput);
            _session.SnapshotChanged += OnSnapshotChanged;
            StopButton.IsEnabled = true;
            var snapshot = await _session.ConnectAsync(EndpointTextBox.Text, _operationCancellation.Token);
            SessionIdentifierText.Text = $"Session identifier: {snapshot.SessionId}. Ephemeral; expires with this run.";
            StopButton.IsEnabled = true;
            SystemSounds.Asterisk.PlayIfEnabled(SoundNotificationCheckBox.IsChecked == true);
        }
        catch (OperationCanceledException)
        {
            ShowError("Connection was cancelled. No capture was submitted.", restoreFocus);
        }
        catch (Exception exception)
        {
            ShowError(SafeFailureMessages.FromException(exception), restoreFocus);
        }
        finally
        {
            SetBusy(false);
            StopButton.IsEnabled = _session?.Snapshot.State is
                RelaySessionState.Connecting or
                RelaySessionState.Negotiating or
                RelaySessionState.ReconnectBackoff or
                RelaySessionState.Active;
            RefreshCaptureButton();
        }
    }

    private async void StopButton_Click(object sender, RoutedEventArgs e)
    {
        var restoreFocus = Keyboard.FocusedElement as UIElement;
        ClearError();
        _operationCancellation?.Cancel();
        try
        {
            if (_session is not null)
            {
                await _session.StopAsync();
            }

            CaptureConsentCheckBox.IsChecked = false;
            CaptureStateText.Text = "Capture state: Off.";
            SessionIdentifierText.Text = "Session identifier: None.";
            StopButton.IsEnabled = false;
        }
        catch (Exception exception)
        {
            ShowError(SafeFailureMessages.FromException(exception), restoreFocus);
        }
        finally
        {
            RefreshCaptureButton();
        }
    }

    private async void SubmitCaptureButton_Click(object sender, RoutedEventArgs e)
    {
        var restoreFocus = Keyboard.FocusedElement as UIElement;
        ClearError();
        if (CaptureConsentCheckBox.IsChecked != true)
        {
            ShowError("Approve exactly one synthetic snapshot before submitting.", restoreFocus);
            return;
        }

        if (_session?.Snapshot.State != RelaySessionState.Active)
        {
            ShowError("Connect the relay before submitting the approved snapshot.", restoreFocus);
            return;
        }

        SetBusy(true);
        CaptureStateText.Text = "Capture state: Submitting one explicitly approved synthetic snapshot.";
        try
        {
            var onePixelPng = SyntheticCaptureAssets.CreateOnePixelPng();
            var outcome = await _session.SubmitAsync(new CaptureSubmission(
                Source: "synthetic-demo",
                Content: onePixelPng,
                Width: 1,
                Height: 1,
                Encoding: ImageEncoding.Png,
                MediaType: "image/png",
                ConsentGranted: true,
                Synthetic: true,
                FrameId: ++_frameId),
                _operationCancellation?.Token ?? CancellationToken.None);
            if (!outcome.Accepted)
            {
                ShowError(outcome.Text, restoreFocus);
                return;
            }

            StatusText.Text = outcome.Delivery switch
            {
                RelayDeliveryStatus.Delivered => $"Result delivered: {outcome.Text}",
                RelayDeliveryStatus.ControlApplied => $"Control applied: {outcome.Text}",
                _ => $"Result accepted: {outcome.Text}",
            };
            AppendHistory($"result {outcome.ResultId} ({outcome.Delivery}): {outcome.Text}");
            SystemSounds.Asterisk.PlayIfEnabled(SoundNotificationCheckBox.IsChecked == true);
        }
        catch (Exception exception)
        {
            ShowError(SafeFailureMessages.FromException(exception), restoreFocus);
        }
        finally
        {
            CaptureConsentCheckBox.IsChecked = false;
            CaptureStateText.Text = "Capture state: Off. Approval consumed; no continuous capture is active.";
            SetBusy(false);
            RefreshCaptureButton();
        }
    }

    private void CaptureConsentChanged(object sender, RoutedEventArgs e) => RefreshCaptureButton();

    private void OnSnapshotChanged(RelaySessionSnapshot snapshot)
    {
        Dispatcher.InvokeAsync(() =>
        {
            SessionStateText.Text = $"Session state: {snapshot.State}. Attempt {snapshot.Attempt}.";
            RefreshCaptureButton();
        });
    }

    private void SetBusy(bool busy)
    {
        _busy = busy;
        ConnectButton.IsEnabled = !busy;
        TransportMode.IsEnabled = !busy;
        EndpointTextBox.IsEnabled = !busy;
        AllowInsecureLoopbackCheckBox.IsEnabled = !busy;
        RefreshCaptureButton();
    }

    private void RefreshCaptureButton()
    {
        SubmitCaptureButton.IsEnabled = !_busy &&
            CaptureConsentCheckBox.IsChecked == true &&
            _session?.Snapshot.State == RelaySessionState.Active;
    }

    private void ClearError()
    {
        ErrorText.Text = string.Empty;
        ErrorText.Visibility = Visibility.Collapsed;
    }

    private void ShowError(string message, UIElement? restoreFocus)
    {
        ErrorText.Visibility = Visibility.Visible;
        ErrorText.Text = $"Error: {message}";
        StatusText.Text = "The operation did not complete. Capture state is unchanged or off.";
        AppendHistory($"error: {message}");
        SystemSounds.Hand.PlayIfEnabled(SoundNotificationCheckBox.IsChecked == true);
        Dispatcher.BeginInvoke(() => restoreFocus?.Focus());
    }

    private void AppendHistory(string line)
    {
        _history.Append(line);
        StatusHistoryTextBox.Text = _history.Text;
        StatusHistoryTextBox.ScrollToEnd();
    }
}

internal static class SystemSoundExtensions
{
    public static void PlayIfEnabled(this SystemSound sound, bool enabled)
    {
        if (enabled)
        {
            sound.Play();
        }
    }
}
