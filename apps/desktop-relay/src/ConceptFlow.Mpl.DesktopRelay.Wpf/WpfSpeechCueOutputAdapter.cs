// SPDX-License-Identifier: MIT OR Apache-2.0

using System.Windows;
using System.Windows.Automation;
using System.Windows.Automation.Peers;
using System.Windows.Controls;
using System.Windows.Markup;
using System.Windows.Threading;
using ConceptFlow.Mpl.DesktopRelay.Core;
using ConceptFlow.Mpl.V1;

namespace ConceptFlow.Mpl.DesktopRelay.Wpf;

internal sealed class WpfSpeechCueOutputAdapter : IRelayCueOutputAdapter
{
    private static readonly IReadOnlyCollection<CueModality> Modalities = [CueModality.Speech];

    private readonly TextBlock _liveRegion;
    private readonly HashSet<string> _activeCueIds = new(StringComparer.Ordinal);
    private readonly Queue<string> _activeCueOrder = new();
    private string? _displayedCueId;

    internal WpfSpeechCueOutputAdapter(TextBlock liveRegion)
    {
        _liveRegion = liveRegion ?? throw new ArgumentNullException(nameof(liveRegion));
    }

    public IReadOnlyCollection<CueModality> SupportedProtocolModalities => Modalities;

    public bool SupportsCancellation => true;

    public bool SupportsSupersession => true;

    public bool CanDeliver(PerceptionCue cue) =>
        cue.Cancel is not null || cue.Supersede is not null || cue.Speech is not null;

    public async Task<bool> DeliverAsync(PerceptionCue cue, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(cue);
        cancellationToken.ThrowIfCancellationRequested();
        await _liveRegion.Dispatcher.InvokeAsync(
            () => DeliverOnUiThread(cue),
            DispatcherPriority.Send,
            cancellationToken).Task.ConfigureAwait(false);
        return true;
    }

    private void DeliverOnUiThread(PerceptionCue cue)
    {
        var controlIds = cue.Cancel?.CueIds ?? cue.Supersede?.CueIds;
        if (controlIds is not null)
        {
            foreach (var cueId in controlIds)
            {
                _activeCueIds.Remove(cueId);
            }

            if (_displayedCueId is not null && controlIds.Contains(_displayedCueId))
            {
                _displayedCueId = null;
                _liveRegion.Text = string.Empty;
            }

            return;
        }

        var speech = cue.Speech ?? throw new InvalidOperationException("A speech cue is required by this adapter.");
        if (_activeCueIds.Add(cue.CueId))
        {
            while (_activeCueOrder.Count >= 128)
            {
                _activeCueIds.Remove(_activeCueOrder.Dequeue());
            }

            _activeCueOrder.Enqueue(cue.CueId);
        }

        _displayedCueId = cue.CueId;
        AutomationProperties.SetLiveSetting(
            _liveRegion,
            CueAttentionPolicy.For(cue) == CueAttention.Assertive
                ? AutomationLiveSetting.Assertive
                : AutomationLiveSetting.Polite);
        if (!string.IsNullOrEmpty(speech.LanguageTag))
        {
            _liveRegion.Language = XmlLanguage.GetLanguage(speech.LanguageTag);
        }

        _liveRegion.Text = speech.Text;
        var peer = UIElementAutomationPeer.FromElement(_liveRegion) ??
            new FrameworkElementAutomationPeer(_liveRegion);
        peer.RaiseAutomationEvent(AutomationEvents.LiveRegionChanged);
    }
}
