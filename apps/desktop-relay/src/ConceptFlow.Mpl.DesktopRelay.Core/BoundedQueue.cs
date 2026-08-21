// SPDX-License-Identifier: MIT OR Apache-2.0

namespace ConceptFlow.Mpl.DesktopRelay.Core;

public sealed record QueueWriteResult<T>(bool Accepted, T? DroppedItem, bool ShouldSignalConsumer = false);

public sealed class BoundedRelayQueue<T>
{
    private readonly int _capacity;
    private readonly QueueOverflowPolicy _overflowPolicy;
    private readonly Queue<T> _items = new();
    private readonly object _gate = new();
    private int _leasedCount;

    public BoundedRelayQueue(int capacity, QueueOverflowPolicy overflowPolicy)
    {
        ArgumentOutOfRangeException.ThrowIfLessThan(capacity, 1);
        _capacity = capacity;
        _overflowPolicy = overflowPolicy;
    }

    public int Count
    {
        get
        {
            lock (_gate)
            {
                return _items.Count + _leasedCount;
            }
        }
    }

    public QueueWriteResult<T> Enqueue(T item)
    {
        lock (_gate)
        {
            if (_items.Count + _leasedCount < _capacity)
            {
                _items.Enqueue(item);
                return new QueueWriteResult<T>(true, default, true);
            }

            if (_overflowPolicy == QueueOverflowPolicy.RejectNewest || _items.Count == 0)
            {
                return new QueueWriteResult<T>(false, default, false);
            }

            var dropped = _items.Dequeue();
            _items.Enqueue(item);
            return new QueueWriteResult<T>(true, dropped, false);
        }
    }

    public bool TryLease(out T? item)
    {
        lock (_gate)
        {
            if (_items.Count == 0)
            {
                item = default;
                return false;
            }

            item = _items.Dequeue();
            _leasedCount++;
            return true;
        }
    }

    public void ReleaseLease()
    {
        lock (_gate)
        {
            if (_leasedCount == 0)
            {
                throw new InvalidOperationException("The bounded queue has no active lease to release.");
            }

            _leasedCount--;
        }
    }

    public bool TryRemove(T item)
    {
        lock (_gate)
        {
            if (_items.Count == 0)
            {
                return false;
            }

            var removed = false;
            var retained = new Queue<T>(_items.Count);
            while (_items.TryDequeue(out var candidate))
            {
                if (!removed && EqualityComparer<T>.Default.Equals(candidate, item))
                {
                    removed = true;
                    continue;
                }

                retained.Enqueue(candidate);
            }

            while (retained.TryDequeue(out var candidate))
            {
                _items.Enqueue(candidate);
            }

            return removed;
        }
    }

    public bool TryDequeue(out T? item)
    {
        lock (_gate)
        {
            if (_items.Count == 0)
            {
                item = default;
                return false;
            }

            item = _items.Dequeue();
            return true;
        }
    }

    public void Clear()
    {
        lock (_gate)
        {
            _items.Clear();
        }
    }

    public IReadOnlyList<T> Drain()
    {
        lock (_gate)
        {
            var drained = _items.ToArray();
            _items.Clear();
            return drained;
        }
    }
}
