// SPDX-License-Identifier: MIT OR Apache-2.0

using System.Security.Cryptography;
using Google.Protobuf;
using Google.Protobuf.WellKnownTypes;
using ProtocolIdentity = ConceptFlow.Mpl.V1.EphemeralIdentity;

namespace ConceptFlow.Mpl.DesktopRelay.Core;

public interface IEphemeralIdentityFactory
{
    string CreateDeviceInstanceId();

    ProtocolIdentity CreateSessionIdentity(TimeSpan lifetime);

    string CreateRequestId();
}

public sealed class EphemeralIdentityFactory : IEphemeralIdentityFactory
{
    private readonly IRelayClock _clock;

    public EphemeralIdentityFactory(IRelayClock? clock = null)
    {
        _clock = clock ?? SystemRelayClock.Instance;
    }

    public string CreateDeviceInstanceId() => $"desktop-{Guid.NewGuid():N}";

    public ProtocolIdentity CreateSessionIdentity(TimeSpan lifetime)
    {
        if (lifetime <= TimeSpan.Zero || lifetime > TimeSpan.FromHours(24))
        {
            throw new ArgumentOutOfRangeException(nameof(lifetime));
        }

        return new ProtocolIdentity
        {
            SessionId = $"session-{Guid.NewGuid():N}",
            Nonce = ByteString.CopyFrom(RandomNumberGenerator.GetBytes(32)),
            ExpiresAt = Timestamp.FromDateTime((_clock.UtcNow + lifetime).UtcDateTime),
        };
    }

    public string CreateRequestId() => $"request-{Guid.NewGuid():N}";
}
