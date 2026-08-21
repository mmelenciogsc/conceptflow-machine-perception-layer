// SPDX-License-Identifier: MIT OR Apache-2.0

using System.Collections.ObjectModel;
using ConceptFlow.Mpl.V1;

namespace ConceptFlow.Mpl.DesktopRelay.Core;

public sealed class NegotiatedRelayPolicy
{
    private static readonly ImageEncoding[] LocalImageEncodings =
    [
        ImageEncoding.Jpeg,
        ImageEncoding.Png,
    ];

    private readonly HashSet<ImageEncoding> _imageEncodings;
    private readonly HashSet<CueModality> _cueModalities;

    private NegotiatedRelayPolicy(
        IEnumerable<ImageEncoding> imageEncodings,
        IEnumerable<CueModality> cueModalities,
        int maximumWidth,
        int maximumHeight,
        int maximumFrameBytes,
        int maximumInFlight,
        int maximumCuesPerResult,
        TimeSpan requestTimeout,
        bool supportsCancellation,
        bool supportsSupersession,
        QueueOverflowPolicy queueOverflowPolicy)
    {
        _imageEncodings = new HashSet<ImageEncoding>(imageEncodings);
        _cueModalities = new HashSet<CueModality>(cueModalities);
        SupportedImageEncodings = new ReadOnlyCollection<ImageEncoding>(_imageEncodings.Order().ToArray());
        SupportedCueModalities = new ReadOnlyCollection<CueModality>(_cueModalities.Order().ToArray());
        MaximumWidth = maximumWidth;
        MaximumHeight = maximumHeight;
        MaximumFrameBytes = maximumFrameBytes;
        MaximumInFlight = maximumInFlight;
        MaximumCuesPerResult = maximumCuesPerResult;
        RequestTimeout = requestTimeout;
        SupportsCancellation = supportsCancellation;
        SupportsSupersession = supportsSupersession;
        QueueOverflowPolicy = queueOverflowPolicy;
    }

    public IReadOnlyList<ImageEncoding> SupportedImageEncodings { get; }

    public IReadOnlyList<CueModality> SupportedCueModalities { get; }

    public int MaximumWidth { get; }

    public int MaximumHeight { get; }

    public int MaximumFrameBytes { get; }

    public int MaximumInFlight { get; }

    public int MaximumCuesPerResult { get; }

    public TimeSpan RequestTimeout { get; }

    public bool SupportsCancellation { get; }

    public bool SupportsSupersession { get; }

    public QueueOverflowPolicy QueueOverflowPolicy { get; }

    internal static bool IsLocallySupportedEncoding(ImageEncoding encoding) =>
        LocalImageEncodings.Contains(encoding);

    internal static NegotiatedRelayPolicy Create(RelayNegotiation negotiation, RelayOptions options)
    {
        ArgumentNullException.ThrowIfNull(negotiation);
        ArgumentNullException.ThrowIfNull(options);
        if (!ProtocolValueValidator.IsIdentifier(negotiation.SessionId))
        {
            throw new InvalidOperationException("Negotiation returned no usable session identifier.");
        }

        var capabilities = negotiation.Capabilities
            ?? throw new InvalidOperationException("Negotiation returned no capability policy.");
        var qualityOfService = negotiation.QualityOfService
            ?? throw new InvalidOperationException("Negotiation returned no quality-of-service policy.");
        if (capabilities.MaxWidth == 0 || capabilities.MaxHeight == 0 || capabilities.MaxFrameBytes == 0)
        {
            throw new InvalidOperationException("Negotiated frame limits must be non-zero.");
        }

        if (qualityOfService.MaxInFlight == 0 || qualityOfService.MaxCuesPerResult == 0)
        {
            throw new InvalidOperationException("Negotiated max-in-flight and cue limits must be non-zero.");
        }

        if (capabilities.ImageEncodings.Count == 0)
        {
            throw new InvalidOperationException("Negotiation returned no supported image encoding.");
        }

        foreach (var encoding in capabilities.ImageEncodings)
        {
            if (encoding == ImageEncoding.Unspecified || !Enum.IsDefined(typeof(ImageEncoding), encoding))
            {
                throw new InvalidOperationException("Negotiation returned a malformed image encoding.");
            }
        }

        var effectiveEncodings = capabilities.ImageEncodings
            .Where(IsLocallySupportedEncoding)
            .Distinct()
            .ToArray();
        if (effectiveEncodings.Length == 0)
        {
            throw new InvalidOperationException("Negotiation returned no image encoding supported by this relay.");
        }

        foreach (var modality in capabilities.CueModalities)
        {
            if (modality == CueModality.Unspecified || !Enum.IsDefined(modality))
            {
                throw new InvalidOperationException("Negotiation returned a malformed cue modality.");
            }
        }

        var effectiveModalities = capabilities.CueModalities
            .Where(options.SupportedCueModalities.Contains)
            .Distinct()
            .ToArray();

        var requestTimeout = options.RequestTimeout;
        if (qualityOfService.ResultDeadline is not null)
        {
            TimeSpan negotiatedDeadline;
            try
            {
                negotiatedDeadline = qualityOfService.ResultDeadline.ToTimeSpan();
            }
            catch (Exception exception) when (exception is InvalidOperationException or OverflowException)
            {
                throw new InvalidOperationException("Negotiation returned a malformed result deadline.", exception);
            }

            if (negotiatedDeadline <= TimeSpan.Zero)
            {
                throw new InvalidOperationException("Negotiated result deadline must be positive.");
            }

            requestTimeout = negotiatedDeadline < requestTimeout ? negotiatedDeadline : requestTimeout;
        }

        var queueOverflowPolicy =
            options.QueueOverflowPolicy == QueueOverflowPolicy.DropOldest && qualityOfService.AllowFrameDrop
                ? QueueOverflowPolicy.DropOldest
                : QueueOverflowPolicy.RejectNewest;
        return new NegotiatedRelayPolicy(
            effectiveEncodings,
            effectiveModalities,
            Math.Min(options.MaximumWidth, checked((int)Math.Min(capabilities.MaxWidth, int.MaxValue))),
            Math.Min(options.MaximumHeight, checked((int)Math.Min(capabilities.MaxHeight, int.MaxValue))),
            Math.Min(options.MaximumFrameBytes, checked((int)Math.Min(capabilities.MaxFrameBytes, int.MaxValue))),
            Math.Min(options.QueueCapacity, checked((int)Math.Min(qualityOfService.MaxInFlight, int.MaxValue))),
            Math.Min(options.MaximumCuesPerResult, checked((int)Math.Min(qualityOfService.MaxCuesPerResult, int.MaxValue))),
            requestTimeout,
            options.SupportsCueCancellation && capabilities.SupportsCancellation,
            options.SupportsCueSupersession && capabilities.SupportsSupersession,
            queueOverflowPolicy);
    }

    internal void Validate(CaptureSubmission submission)
    {
        if (!_imageEncodings.Contains(submission.Encoding))
        {
            throw new NotSupportedException($"Image encoding {submission.Encoding} was not negotiated for this session.");
        }

        if (submission.Width > MaximumWidth || submission.Height > MaximumHeight)
        {
            throw new ArgumentOutOfRangeException(
                nameof(submission),
                $"Dimensions exceed the negotiated {MaximumWidth} by {MaximumHeight} pixel limit.");
        }

        if (submission.Content.Length > MaximumFrameBytes)
        {
            throw new ArgumentOutOfRangeException(
                nameof(submission),
                $"Content exceeds the negotiated {MaximumFrameBytes} byte limit.");
        }
    }
}
