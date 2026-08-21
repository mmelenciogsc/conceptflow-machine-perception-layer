// SPDX-License-Identifier: MIT OR Apache-2.0

using ConceptFlow.Mpl.DesktopRelay.Core;

namespace ConceptFlow.Mpl.DesktopRelay.Core.Tests;

public sealed class RedactionAndIdentityTests
{
    [Fact]
    public void Redact_RemovesCommonSecretShapesAndUserInfo()
    {
        var raw = "Bearer abc123 token=xyz password=hunter2 https://alice:secret@example.test/path";

        var safe = StatusRedactor.Redact(raw);

        Assert.DoesNotContain("abc123", safe, StringComparison.Ordinal);
        Assert.DoesNotContain("xyz", safe, StringComparison.Ordinal);
        Assert.DoesNotContain("hunter2", safe, StringComparison.Ordinal);
        Assert.DoesNotContain("alice:secret", safe, StringComparison.Ordinal);
        Assert.Contains("[redacted]", safe, StringComparison.Ordinal);
    }

    [Fact]
    public void SafeStatus_StoresOnlyEndpointOrigin()
    {
        var status = SafeStatus.Create(
            "connected",
            "ready",
            "request valid/id",
            new Uri("https://example.test:7443/private/path"));

        Assert.Equal("https://remote:7443", status.EndpointOrigin);
        Assert.DoesNotContain("example.test", status.EndpointOrigin, StringComparison.Ordinal);
        Assert.Equal("[invalid-id]", status.CorrelationId);
    }

    [Fact]
    public void Redact_RemovesEmailPathsHostnamesAndDisplayControls()
    {
        const string raw = "person@example.com C:\\private\\secret.txt /tmp/secret relay.internal\nforged";

        var safe = StatusRedactor.Redact(raw);

        Assert.DoesNotContain("person@example.com", safe, StringComparison.Ordinal);
        Assert.DoesNotContain("secret.txt", safe, StringComparison.Ordinal);
        Assert.DoesNotContain("/tmp", safe, StringComparison.Ordinal);
        Assert.DoesNotContain("relay.internal", safe, StringComparison.Ordinal);
        Assert.DoesNotContain('\n', safe);
    }

    [Fact]
    public void BoundedTextHistory_BoundsAfterAppendBeforeBuildingAccessibleText()
    {
        var history = new BoundedTextHistory(
            maximumCharacters: 120,
            maximumEntries: 3,
            maximumLineCharacters: 40);

        history.Append(new string('x', 1_000_000));
        history.Append("attention\nforged\u202Etext");
        history.Append("third");
        history.Append("fourth");

        Assert.InRange(history.Text.Length, 1, 120);
        Assert.InRange(history.Count, 1, 3);
        Assert.DoesNotContain('\u202E', history.Text);
        Assert.DoesNotContain("attention\nforged", history.Text, StringComparison.Ordinal);
    }

    [Fact]
    public void IdentityFactory_GeneratesDistinctEphemeralValues()
    {
        var factory = new EphemeralIdentityFactory(new FakeRelayClock());

        var first = factory.CreateSessionIdentity(TimeSpan.FromMinutes(5));
        var second = factory.CreateSessionIdentity(TimeSpan.FromMinutes(5));

        Assert.NotEqual(first.SessionId, second.SessionId);
        Assert.NotEqual(first.Nonce, second.Nonce);
        Assert.Equal(32, first.Nonce.Length);
        Assert.StartsWith("desktop-", factory.CreateDeviceInstanceId(), StringComparison.Ordinal);
    }
}
