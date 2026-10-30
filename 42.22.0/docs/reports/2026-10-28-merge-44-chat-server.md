# Review 44/48: ChatServer

Created 2026-10-28. Status: **approved and applied on 2026-10-28**.
Review43 is applied. This one-class patch is also applied after baseline 196d5da. See the
[subsystem tracker](../features/2026-10-27-subsystem-port-tracker.md),
[candidate diff](2026-10-28-merge-44-chat-server.diff) and
[verification record](2026-10-28-merge-44-chat-server.json).

## Old custom intent

The [42.21 patch](../../../42.21.0/src/zombie/network/chat/ChatServer.java)
adds two facilities to [old vanilla](../../../42.21.0/decompiled/zombie/network/chat/ChatServer.java):

- After native filtering and send, forward only general player chat to
  ApocBRBackendBridgeHooks.onChat(connection,author,text,"general"). The existing
  hook separately resolves a live player from the sender's slots before capture;
  delivery remains behind the existing backend worker.
- sendHumanSay, humanShoutRange and sendHumanShout let trusted server NPC transport
  send ordinary say/yell presentation to explicit real recipients. Detached NPC
  authors have no chat account/global live-player registration. Explicit routing
  avoids RangeBasedChat's real-player-author lookup.

## Native changes and candidate placement

[42.22 vanilla](../../decompiled/zombie/network/chat/ChatServer.java) adds checks
for unknown channel, channel membership, forbidden client use of the server
channel, and connection ownership of the message author. Channel enablement,
filter policy and bad-word replacement follow those checks. It also corrects the
mute path's player-null test. Whisper requests now receive the connection and
verify ownership of the requested author before constructing a private chat.

Keep all these changes. The backend callback sits inside the successful native
player-message branch, **after replacement and native send**, only for general.
It does not move ahead of ownership, publish non-general messages or add an NPC
fallback to untrusted inbound messages. Native ban/kick/log switch behavior is
retained, including its existing continuation after those branches; the candidate
does not invent additional early returns.

Copy the three old NPC helper bodies unchanged. They require initialized chat,
a registered real recipient and an enabled/default say or shout channel. They
set an explicit NPC author and call ChatBase.sendMessageToPlayer(short,message).
No NPC chat account or real connection is fabricated, and no player/network
packet identity rule is weakened.

[ServerHumanTransport](../../src/zombie/apocalipse/humans/ServerHumanTransport.java)
retains relevance/floor/range checks and one send attempt per viewing connection.
Say uses its existing 30-tile gate; shout uses the configured native chat range.
The helpers themselves do not enforce distance, so they remain trusted server APIs.
True reports a routing attempt, not acknowledgement: the native short-recipient
send silently does nothing if the connection disappears after chat registration.

## Verification

[Fixture](../../../tools/test_chat_server_port.py): **43 assertions passed**.
It extracts and executes exact candidate player-message/whisper/sendMessage and
NPC helper bodies, plus the target ChatBase short-recipient method, against
recording ports. Removing the four custom additions restores the complete target
text; the three NPC helper bodies are identical to the previous patch.

Coverage includes rejection before unpack for unknown/unauthorized/server chats,
spoofed authors, disabled channels, filtered general-chat capture, exclusion of
say/shout from that capture, mute/no-publication, unchanged ban/kick/log flow,
no callback after a send exception, whisper author ownership/missing target/pair
creation, initialized/recipient/channel helper gates, explicit NPC attribution,
configured shout range and the vanished-recipient no-delivery behavior.

Chat/channel membership, actual message encoding, word filtering, logger,
connections and backend hook are doubles. This does not compile all ChatServer,
execute backend authentication/publishing workers, deliver HTTP/Discord/network
traffic or render NPC speech in a live client. Those integrations and full game
JAR/runtime/threading remain unverified. The real backend hook's extra ownership
checks and transport range guards were source-reviewed rather than executed here.

## Decision

The user approved this patch. Exact target baseline 196d5da was committed
locally, then the reviewed diff applied. Applied-candidate equality and 43
assertions passed again. No deployment or installed-file changes occurred.

Next is [review45: AdvancedAnimator](2026-10-28-merge-45-advanced-animator.md),
a separate unapplied missing-optional-folder guard.
