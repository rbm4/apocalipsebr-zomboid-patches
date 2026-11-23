# Async chunk replies

In original 42.22.2, `PlayerDownloadServer.update()` handles download requests
on the server loop, snapshots loaded chunks, and submits the snapshots to the
per-connection download worker. The worker compresses the payload, reads
chunks from disk when needed, and sends `SentChunk`. The server loop also
sends some `NotRequiredInZip` and `ChunkNotReady` replies inline.

The agent routes those inline replies through the same worker. The worker
preserves the native packet header, fields, priority, reliability and ordering
channel, with its own direct buffer for the new replies. The status batch
precedes the payload command from the same update. The server does not take a
connection send-buffer lock or wait for a worker result when handing off the
batch. The existing native chunk serializers and compression paths still run
on their original threads.

Each connection can have one status batch in flight. The update stops taking
more bookkeeping items after 236 replies, with space for up to 20 additional
replies from serialization errors in the native chunk batch. Requests that
have yet to be processed stay in their native queues or lists. The following
updates for that connection yield while the worker is still sending its batch.
A transport failure is recorded and the worker continues. Client request
handling remains vanilla.

`UdpEngine.removeConnection` retires the exact connection's worker.
`PlayerDownloadServer.destroy()` detaches it without a join. Retirement
discards queued work and prevents subsequent send attempts. A send already
entering the native transport can finish. Final peer shutdown joins the
remaining workers before calling native `Shutdown` so the peer cannot be
freed during a send. Set
`-Dapocbr.optimization.chunkAsyncSend.enabled=false` to restore the vanilla
paths at JVM startup.

## Telemetry and validation

- `players.relevanceAndDownloads.downloadUpdate.serialize`,
  `.pending` and `.deduplicate` measure the corresponding server-thread
  work inside the download phase. Serialization still reads live world
  state and has no elapsed-time cap.
- `network.chunks.async.statusTransport` and `.payloadTransport` report
  worker wall time for packet preparation and transport. These window
  phases can overlap simulation. `network.chunks.async.backpressure`
  counts skipped connection updates while a status batch is in flight.
  `network.chunks.async.statusSendFailed` counts failed send attempts for
  the newly deferred replies.
- `ChunkAsyncSendTest` runs the installed hooks on the original game JAR
  with a controlled transport. It covers the enable flag, status packet
  headers and ordering, backpressure, transport failure, continued vanilla
  payload delivery, disconnect, and final peer shutdown.
- On Windows with Java 25, Maven packaging completed with 141 tests,
  no failures or errors, and 3 skipped. The isolated fixture also passed
  against the shaded agent with the feature enabled and disabled. The
  original game archive SHA-256 was
  `3C4A7C5F149D784758C6FB5F8FA85F3A21D572E38BC081400E89035F8120D287`.
- Live client streaming, RakNet behavior under congestion and impact on
  the production spike require live validation.
