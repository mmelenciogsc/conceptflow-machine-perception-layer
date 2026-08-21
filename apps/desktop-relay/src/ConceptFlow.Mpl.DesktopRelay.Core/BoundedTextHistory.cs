// SPDX-License-Identifier: MIT OR Apache-2.0

using System.Buffers;
using System.Globalization;
using System.Text;

namespace ConceptFlow.Mpl.DesktopRelay.Core;

public sealed class BoundedTextHistory
{
    public const int DefaultMaximumCharacters = 8_000;
    public const int DefaultMaximumEntries = 64;
    public const int DefaultMaximumLineCharacters = 512;

    private readonly Queue<string> _lines = new();
    private readonly int _maximumCharacters;
    private readonly int _maximumEntries;
    private readonly int _maximumLineCharacters;

    public BoundedTextHistory(
        int maximumCharacters = DefaultMaximumCharacters,
        int maximumEntries = DefaultMaximumEntries,
        int maximumLineCharacters = DefaultMaximumLineCharacters)
    {
        ArgumentOutOfRangeException.ThrowIfLessThan(maximumCharacters, 1);
        ArgumentOutOfRangeException.ThrowIfLessThan(maximumEntries, 1);
        ArgumentOutOfRangeException.ThrowIfLessThan(maximumLineCharacters, 1);
        _maximumCharacters = maximumCharacters;
        _maximumEntries = maximumEntries;
        _maximumLineCharacters = Math.Min(maximumLineCharacters, maximumCharacters);
    }

    public int Count => _lines.Count;

    public string Text => string.Join(Environment.NewLine, _lines);

    public void Append(string? line)
    {
        var bounded = StatusRedactor.Redact(line);
        bounded = DisplayTextSanitizer.Truncate(bounded, _maximumLineCharacters, appendEllipsis: false);

        _lines.Enqueue(bounded);
        while (_lines.Count > _maximumEntries || CurrentLength() > _maximumCharacters)
        {
            _lines.Dequeue();
        }
    }

    private int CurrentLength() => _lines.Sum(line => line.Length) +
        Math.Max(0, _lines.Count - 1) * Environment.NewLine.Length;
}

internal static class DisplayTextSanitizer
{
    internal static string Truncate(string value, int maximumCharacters, bool appendEllipsis)
    {
        if (value.Length <= maximumCharacters)
        {
            return value;
        }

        var contentLength = appendEllipsis ? maximumCharacters - 1 : maximumCharacters;
        if (contentLength > 0 && char.IsHighSurrogate(value[contentLength - 1]))
        {
            contentLength--;
        }

        return appendEllipsis ? value[..contentLength] + "…" : value[..contentLength];
    }

    internal static string Sanitize(string value)
    {
        var builder = new StringBuilder(value.Length);
        var remaining = value.AsSpan();
        while (!remaining.IsEmpty)
        {
            var status = Rune.DecodeFromUtf16(remaining, out var rune, out var consumed);
            if (status != OperationStatus.Done)
            {
                builder.Append('\uFFFD');
                remaining = remaining[1..];
                continue;
            }

            var category = Rune.GetUnicodeCategory(rune);
            if (category is UnicodeCategory.Control or UnicodeCategory.Format or
                UnicodeCategory.Surrogate or UnicodeCategory.PrivateUse or
                UnicodeCategory.OtherNotAssigned or UnicodeCategory.LineSeparator or
                UnicodeCategory.ParagraphSeparator)
            {
                builder.Append(' ');
            }
            else
            {
                builder.Append(rune.ToString());
            }

            remaining = remaining[consumed..];
        }

        return builder.ToString();
    }
}
