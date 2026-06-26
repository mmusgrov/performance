# Corrected Performance Analysis: HQStore vs JGroupsSlots

**Date**: 2026-06-26  
**Measured Performance**:
- HQStore: 2,526 ops/s
- JGroupsSlots + WAL + fsync: 853,020 ops/s
- **Ratio**: 338× faster (not 460× as previously stated)

---

## Review of Previous Analysis Errors

My initial analysis in `ARTEMIS_JOURNAL_COMPARISON.md` made several incorrect assumptions:

1. ❌ **Claimed JGroupsSlots has "no serialization"** - WRONG
2. ❌ **Underestimated serialization overhead in both stores**
3. ❌ **Didn't account for SlotStore.write() byte packing**
4. ❌ **Claimed significant difference in object creation** - Both use OutputBuffer/ByteBuffer
5. ❌ **Called it "file-based vs in-memory"** - WRONG, both write to Artemis Journal files

Let me trace the actual code paths:

---

## Actual Write Paths (Code-Level Analysis)

### HQStore Write Path

**Entry**: `HornetqObjectStoreAdaptor.write_committed(Uid, typeName, OutputObjectState)`
```
1. HornetqJournalStore.write_committed(Uid, typeName, OutputObjectState)
2.   Create OutputBuffer
3.   UidHelper.packInto(uid, outputBuffer)           // Pack Uid (~16 bytes)
4.   outputBuffer.packString(typeName)               // Pack typeName string
5.   outputBuffer.packBytes(txData.buffer())         // Pack transaction state
6.   byte[] data = outputBuffer.buffer()
7.   Create RecordInfo(id, type, data, ...)
8.   Update ConcurrentMap<String, ConcurrentMap<Uid, RecordInfo>>
9.     getContentForType(typeName).put/replace(uid, record)
10.  journal.appendAddRecord(id, type, data, syncWrites=true)  // Artemis Journal
```

**Serialization**:
- UidHelper.packInto: packs Uid bytes
- packString(typeName): packs string length + bytes
- packBytes(state): packs state length + bytes
- Total: Uid (16 bytes) + typeName (length varies) + state

---

### JGroupsSlots Write Path

**Entry**: `SlotStoreAdaptor.write_committed(Uid, typeName, OutputObjectState)`
```
1. SlotStore.write(SlotStoreKey, OutputObjectState)
2.   Create OutputBuffer record
3.   key.packInto(record):
4.     UidHelper.packInto(uid, record)              // Pack Uid (~16 bytes)
5.     record.packString(typeName)                  // Pack typeName string
6.     record.packInt(stateStatus)                  // Pack int (4 bytes)
7.   outputObjectState.packInto(record)             // Pack transaction state
8.   byte[] data = record.buffer()
9.   slotId = freeList.poll()
10.  slots.write(slotId, data, syncWrites)
11.    JGroupsSlots.write(slot, data, sync):
12.      journal.write(slot, data)                   // SlotJournal (Artemis)
13.        ByteBuffer buffer = allocate(4 + 4 + data.length)
14.        buffer.putInt(slotId)                     // 4 bytes
15.        buffer.putInt(data.length)                // 4 bytes
16.        buffer.put(data)                          // data bytes
17.        journal.appendAddRecord(id, type, buffer.array(), syncWrites=true)
18.      cache.put(slots[slot], data, ...)           // JGroups ReplCache
19.  slotIdIndex.put(key, slotId)                    // Update HashMap
```

**Serialization**:
- UidHelper.packInto: packs Uid bytes (same as HQStore)
- packString(typeName): packs string length + bytes (same as HQStore)
- packInt(stateStatus): packs 4 bytes
- packInto(state): packs state
- SlotJournal adds: slotId (4 bytes) + length (4 bytes)

---

## Corrected Overhead Comparison

### Serialization: BOTH DO THE SAME THING

| Operation | HQStore | JGroupsSlots | Difference |
|-----------|---------|--------------|------------|
| Pack Uid | UidHelper.packInto | UidHelper.packInto | **Same** |
| Pack typeName | outputBuffer.packString | record.packString | **Same** |
| Pack state | outputBuffer.packBytes | outputObjectState.packInto | **Same** |
| Extra data | None | +stateStatus (4 bytes) + slotId (4 bytes) + length (4 bytes) | **JGroupsSlots packs MORE** |

**Conclusion**: Serialization overhead is **THE SAME** (in fact, JGroupsSlots packs 12 extra bytes).

---

### Object Creation: BOTH CREATE OUTPUT BUFFERS

| Object | HQStore | JGroupsSlots |
|--------|---------|--------------|
| OutputBuffer | ✅ 1 instance | ✅ 1 instance |
| ByteBuffer | ❌ (uses byte[] directly) | ✅ 1 instance (in SlotJournal) |
| RecordInfo | ✅ 1 instance | ❌ |

**Conclusion**: Object creation overhead is **SIMILAR**, not a significant difference.

---

### Data Structure Complexity: THIS IS THE KEY DIFFERENCE

**HQStore**:
```java
ConcurrentMap<String, ConcurrentMap<Uid, RecordInfo>> content
```

Write operation:
1. `getContentForType(typeName)` - HashMap lookup by String
2. `.put(uid, record)` or `.replace(uid, record)` - HashMap lookup by Uid
3. Create/update RecordInfo object
4. **Total: 2 ConcurrentHashMap operations**

Read operation:
1. `getContentForType(typeName)` - HashMap lookup by String
2. `.get(uid)` - HashMap lookup by Uid
3. Access RecordInfo.data
4. Create InputBuffer from data
5. Unpack Uid, typeName, state
6. **Total: 2 ConcurrentHashMap operations + unpacking**

---

**JGroupsSlots**:
```java
ConcurrentMap<SlotStoreKey, Integer> slotIdIndex    // key → slot mapping
ByteArrayKey[] slots                                 // Direct array (slot → cache key)
```

Write operation:
1. `freeList.poll()` - ConcurrentLinkedQueue operation (fast)
2. `slotIdIndex.put(key, slotId)` - 1 HashMap operation
3. Direct array access: `slots[slotId]`
4. **Total: 1 ConcurrentHashMap operation + 1 array access**

Read operation:
1. `slotIdIndex.get(key)` - 1 HashMap operation
2. Direct array access: `slots[slotId]`
3. `cache.get(slots[slotId])` - ReplCache operation
4. **Total: 1 ConcurrentHashMap operation + 1 array access**

---

## Quantifying HashMap Overhead

Based on typical ConcurrentHashMap performance:
- Single get/put: ~50-100ns (warm cache, no contention)
- With contention (10 threads): ~200-500ns per operation

**HQStore**: 2 HashMap operations per access  
**JGroupsSlots**: 1 HashMap operation per access

**Difference**: ~100-250ns saved per operation

**This alone cannot explain the 338× difference!**

---

## The Real Difference: Artemis Journal Usage Pattern

Let me check how each store uses the Artemis Journal:

**HQStore**:
- Stores: Uid + typeName + state in ONE journal record
- Journal record data includes: **all metadata + transaction state**
- On every access: journal must maintain the RecordInfo in memory

**JGroupsSlots**:
- Stores: slotId + length + (Uid + typeName + stateStatus + state) in ONE journal record
- But ALSO writes to: **ReplCache** (in-memory cache)
- On read: reads from **cache**, NOT journal

---

## AHA! The Cache vs No-Cache Difference

Looking at the read paths more carefully:

**HQStore read_committed**:
```java
RecordInfo record = getContentForType(typeName).get(uid);
InputBuffer inputBuffer = new InputBuffer(record.data);
Uid uid = UidHelper.unpackFrom(inputBuffer);
String typeName = inputBuffer.unpackString();
// ... unpack state
return new InputObjectState(uid, typeName, state);
```
- Reads from in-memory RecordInfo
- Then UNPACKS data every time

**JGroupsSlots read**:
```java
Integer slotId = slotIdIndex.get(key);
byte[] data = cache.get(slots[slotId]);  // ReplCache L1/L2
// OR if not in cache:
data = journal.read(slotId);              // From SlotJournal in-memory map
return new InputObjectState(data);        // Direct bytes, no unpacking!
```
- Reads from ReplCache L2 (local HashMap) - very fast
- Returns raw bytes directly
- **No unpacking on read!**

---

## Where the 338× Comes From: The Unpacking on EVERY Read

Let me check what happens in transaction processing...

Actually, looking at the JMH benchmark (JTAStoreBase.jtaTest()):
```java
1. Begin transaction
2. Enlist XA resource
3. Commit transaction
4. Transaction manager writes state to object store
5. Recovery manager scans object store
6. Recovery manager reads states back
```

The benchmark does BOTH writes AND reads!

**Key insight**: HQStore unpacks Uid + typeName on EVERY read, even though it already knows these values (it used them as the lookup key!).

**HQStore read path** (from code above):
```java
// We already HAVE uid and typeName as method parameters!
public InputObjectState read_committed(Uid uid, String typeName) {
    RecordInfo record = getContentForType(typeName).get(uid);
    InputBuffer inputBuffer = new InputBuffer(record.data);
    
    // But we STILL unpack them from the data!
    Uid readUid = UidHelper.unpackFrom(inputBuffer);      // Redundant!
    String readTypeName = inputBuffer.unpackString();     // Redundant!
    byte[] state = remaining bytes...
    
    return new InputObjectState(readUid, readTypeName, state);
}
```

This is **incredibly wasteful** - unpacking data we already know!

---

## Quantifying the Unpacking Overhead

Let's estimate unpacking time:
- `UidHelper.unpackFrom()`: read byte count, read bytes, create Uid - ~200ns
- `unpackString()`: read length, read bytes, create String - ~300ns
- Creating new InputObjectState: ~100ns

**Total unpacking overhead per read**: ~600ns = 0.6μs

**But wait**: 
- HQStore: 2,526 ops/s = 396μs per operation
- Unpacking: 0.6μs
- **Unpacking is only 0.15% of the time!**

This still doesn't explain it!

---

## Let Me Reconsider: What Are We Actually Measuring?

Looking at the JMH benchmark again, it measures **transaction throughput**, not just object store operations.

But the object store is called multiple times per transaction:
1. Write prepared state
2. Write committed state  
3. Remove prepared state
4. Recovery scan (reads many states)

Let me check what's REALLY different by looking at the Artemis Journal configuration...

---

## The REAL Root Cause: Journal Write Batching Behavior

Both use Artemis Journal with `syncWrites=true` and `bufferFlushesPerSecond=300`.

**But there's a critical difference in HOW they call the journal**:

**HQStore** (HornetqJournalStore.java line 194):
```java
journal.appendAddRecord(record.id, RECORD_TYPE, data, syncWrites);
```
When `syncWrites=true`, this calls `journal.appendAddRecord(id, type, data, true)`

**JGroupsSlots** (SlotJournal.java line 197):
```java
journal.appendAddRecord(newRecordId, RECORD_TYPE, encoded, syncWrites);
```
Same call!

**So both are calling the SAME Artemis Journal method with the SAME parameters.**

The difference MUST be in what happens AROUND the journal call.

---

## Profiling Hypothesis

Given that both stores:
1. Do similar serialization (pack Uid + typeName + state)
2. Call the same Artemis Journal API
3. Use similar HashMap structures

The 338× difference must come from:

**Option A**: Lock contention in HQStore's 2-level ConcurrentHashMap  
**Option B**: RecordInfo object creation/GC pressure  
**Option C**: ReplCache is MUCH faster than ConcurrentHashMap  
**Option D**: Something else I'm missing in the transaction processing path  

---

## Testing the Hypothesis

To properly quantify the difference, I would need to:

1. **Profile HQStore** with JFR (Java Flight Recorder)
   - Measure time spent in HashMap operations
   - Measure time spent in serialization/deserialization
   - Measure lock contention

2. **Profile JGroupsSlots** with JFR
   - Same measurements for comparison

3. **Create a micro-benchmark** that isolates:
   - Just HashMap operations (2-level vs 1-level)
   - Just serialization (with/without unpacking)
   - Just Artemis Journal calls

Would you like me to run JFR profiling on both benchmarks to get actual measurements?

---

## Corrected Statement on "File-Based"

You're absolutely right - I incorrectly stated:
> "JGroups stores dominate: Even with full fsync durability, 148-223× faster than traditional file-based stores"

This is misleading because:
- **JGroupsSlots + WAL** writes to **SlotJournal**, which writes to **Artemis Journal files**
- **HQStore** writes to **HornetqJournalStore**, which writes to **Artemis Journal files**

**Both are file-based stores using Artemis Journal!**

The correct statement should be:
> "JGroupsSlots + WAL is 338× faster than HQStore, despite both using the same underlying Artemis Journal for file-based persistence. The difference comes from the object store architecture around the journal, not the journal itself."

---

## What We Actually Know

**Measured facts**:
1. HQStore: 2,526 ops/s (396μs per op)
2. JGroupsSlots + WAL: 853,020 ops/s (1.17μs per op)
3. Both use Artemis Journal NIO (no real fsync on this system)
4. Both serialize Uid + typeName + state
5. HQStore uses 2-level ConcurrentHashMap
6. JGroupsSlots uses 1-level ConcurrentHashMap + array

**Hypotheses to test**:
1. Lock contention in 2-level HashMap (needs profiling to quantify)
2. RecordInfo creation overhead (needs profiling to quantify)  
3. Unpacking on read (estimated ~0.6μs, too small to matter)
4. Something in the transaction processing path I haven't identified

**I cannot accurately quantify the HashMap or RecordInfo overhead without profiling data.**

Would you like me to:
1. Run JFR profiling on both benchmarks?
2. Create isolated micro-benchmarks for each component?
3. Something else?

---

## Summary

My previous analysis was **incorrect** in claiming:
- ❌ "No serialization" - both serialize the same data
- ❌ "No object creation" - both create OutputBuffer
- ❌ "File-based vs in-memory" - both are file-based (Artemis Journal)

The **actual difference** is somewhere in:
- HashMap structure complexity (2-level vs 1-level)
- RecordInfo object overhead
- ReplCache performance
- Possibly transaction manager interaction patterns

**I need profiling data to accurately quantify these differences.**
