// SPDX-License-Identifier: MIT OR Apache-2.0

using System.Text.RegularExpressions;

namespace ConceptFlow.Mpl.DesktopRelay.Core;

public sealed record SafeStatus(
    string Code,
    string Message,
    string? CorrelationId = null,
    string? EndpointOrigin = null)
{
    public static SafeStatus Create(
        string code,
        string message,
        string? correlationId = null,
        Uri? endpoint = null) => new(
            StatusRedactor.Redact(code),
            StatusRedactor.Redact(message),
            StatusRedactor.RedactIdentifier(correlationId),
            endpoint is null ? null : StatusRedactor.EndpointOrigin(endpoint));
}

public static partial class StatusRedactor
{
    private const string Redacted = "[redacted]";

    public static string Redact(string? value)
    {
        if (string.IsNullOrEmpty(value))
        {
            return string.Empty;
        }

        var boundedInput = DisplayTextSanitizer.Truncate(value, 2_048, appendEllipsis: false);
        var sanitized = BearerPattern().Replace(boundedInput, "$1" + Redacted);
        sanitized = SecretPattern().Replace(sanitized, "$1=" + Redacted);
        sanitized = UserInfoPattern().Replace(sanitized, "$1" + Redacted + "@");
        sanitized = EndpointPattern().Replace(sanitized, "[endpoint]");
        sanitized = EmailPattern().Replace(sanitized, Redacted);
        sanitized = FilePathPattern().Replace(sanitized, "[path]");
        sanitized = HostnamePattern().Replace(sanitized, "[host]");
        sanitized = DisplayTextSanitizer.Sanitize(sanitized);
        return DisplayTextSanitizer.Truncate(sanitized, 512, appendEllipsis: true);
    }

    public static string? RedactIdentifier(string? identifier)
    {
        if (string.IsNullOrWhiteSpace(identifier))
        {
            return null;
        }

        return ProtocolValueValidator.IsIdentifier(identifier) ? identifier : "[invalid-id]";
    }

    public static string EndpointOrigin(Uri endpoint)
    {
        ArgumentNullException.ThrowIfNull(endpoint);
        var classification = endpoint.IsLoopback ? "loopback" : "remote";
        var port = endpoint.IsDefaultPort ? string.Empty : $":{endpoint.Port}";
        return $"{endpoint.Scheme}://{classification}{port}";
    }

    [GeneratedRegex("(?i)(bearer\\s+)[^\\s,;]+", RegexOptions.CultureInvariant)]
    private static partial Regex BearerPattern();

    [GeneratedRegex("(?i)(token|secret|password|api[_-]?key)\\s*=\\s*[^\\s,;]+", RegexOptions.CultureInvariant)]
    private static partial Regex SecretPattern();

    [GeneratedRegex("(https?://)[^/@\\s]+@", RegexOptions.CultureInvariant)]
    private static partial Regex UserInfoPattern();

    [GeneratedRegex("(?i)https?://[^\\s/]+", RegexOptions.CultureInvariant)]
    private static partial Regex EndpointPattern();

    [GeneratedRegex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b", RegexOptions.CultureInvariant)]
    private static partial Regex EmailPattern();

    [GeneratedRegex("(?i)(?:\\b[A-Z]:\\\\|/)[^\\s]+", RegexOptions.CultureInvariant)]
    private static partial Regex FilePathPattern();

    [GeneratedRegex("(?i)\\b(?:[A-Z0-9-]+\\.)+(?:com|internal|invalid|local|net|org|test)\\b", RegexOptions.CultureInvariant)]
    private static partial Regex HostnamePattern();
}

public static class SafeFailureMessages
{
    public static string FromException(Exception exception)
    {
        ArgumentNullException.ThrowIfNull(exception);
        return exception switch
        {
            OperationCanceledException => "The operation was cancelled safely.",
            TimeoutException => "The relay operation exceeded its bounded deadline.",
            EndpointValidationException => "The endpoint was rejected by the secure transport policy.",
            NotSupportedException => "The requested format or capability is not supported.",
            ArgumentException => "Submitted data did not pass local validation.",
            InvalidOperationException => "The operation is not available in the current relay state.",
            IOException => "The relay service is unavailable.",
            _ => "The relay operation failed safely.",
        };
    }
}
