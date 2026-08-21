// SPDX-License-Identifier: MIT OR Apache-2.0

using System.Net;

namespace ConceptFlow.Mpl.DesktopRelay.Core;

public sealed record ValidatedEndpoint(Uri Uri, bool UsesTls, bool IsDevelopmentLoopback);

public sealed class EndpointValidationException : ArgumentException
{
    public EndpointValidationException(string message)
        : base(message)
    {
    }
}

public static class EndpointPolicy
{
    public static ValidatedEndpoint Validate(string? value, bool allowInsecureLoopbackForDevelopment = false)
    {
        if (string.IsNullOrWhiteSpace(value) ||
            !Uri.TryCreate(value.Trim(), UriKind.Absolute, out var endpoint))
        {
            throw new EndpointValidationException("Enter an absolute HTTPS endpoint.");
        }

        if (!string.IsNullOrEmpty(endpoint.UserInfo) ||
            !string.IsNullOrEmpty(endpoint.Query) ||
            !string.IsNullOrEmpty(endpoint.Fragment))
        {
            throw new EndpointValidationException("Endpoint credentials, query strings, and fragments are not allowed.");
        }

        if (string.IsNullOrWhiteSpace(endpoint.Host) || endpoint.AbsolutePath is not ("" or "/"))
        {
            throw new EndpointValidationException("Endpoint must be a server origin without an application path.");
        }

        if (endpoint.Scheme.Equals(Uri.UriSchemeHttps, StringComparison.OrdinalIgnoreCase))
        {
            return new ValidatedEndpoint(endpoint, UsesTls: true, IsDevelopmentLoopback: IsLoopback(endpoint));
        }

        if (endpoint.Scheme.Equals(Uri.UriSchemeHttp, StringComparison.OrdinalIgnoreCase) &&
            allowInsecureLoopbackForDevelopment && IsLoopback(endpoint))
        {
            return new ValidatedEndpoint(endpoint, UsesTls: false, IsDevelopmentLoopback: true);
        }

        throw new EndpointValidationException(
            "HTTPS/TLS is required. HTTP is permitted only for loopback when the explicit development option is enabled.");
    }

    private static bool IsLoopback(Uri endpoint)
    {
        if (endpoint.Host.Equals("localhost", StringComparison.OrdinalIgnoreCase))
        {
            return true;
        }

        return IPAddress.TryParse(endpoint.Host, out var address) && IPAddress.IsLoopback(address);
    }
}
