// SPDX-License-Identifier: MIT OR Apache-2.0

namespace ConceptFlow.Mpl.DesktopRelay.Core;

public static class SyntheticCaptureAssets
{
    private const string OnePixelPngBase64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=";

    public static byte[] CreateOnePixelPng() => Convert.FromBase64String(OnePixelPngBase64);
}
