# DoubleLife audit events

DoubleLife emits best-effort events through its shaded Mysterria audit client
(`dev.ua.ikeepcalm.mysterria:audit-client`, relocated to
`dev.ua.ikeepcalm.doublelife.libs.audit`). Producer id is `doublelife`; the spool
directory is `plugins/mysterria-audit-spool`. If the client cannot start, DoubleLife
logs one warning and keeps working without audit rows. Every emit is guarded, so an
audit failure never changes a session, a command or a message.

Rows are emitted **when the activity happens**, not at session end. The existing
text log (`plugins/DoubleLife/logs`), Discord/callback reports and risk scoring are
unchanged and still use the in-memory activity list. That list is now also saved
in the session file, so a resumed session keeps its history.

## Envelope conventions

- **correlationId / businessId**: the `session_id` (a UUID made at session start and
  saved in `plugins/DoubleLife/sessions/<name>.yml`, so it survives a restart and
  resume). It is also written to the `session_id` metadata key. Rows outside a session
  use a random correlation id and the subject UUID as businessId.
- **actorId**: the staff player acting. It is left empty for system actions (LuckPerms
  node changes, gamemode changes made by DoubleLife itself, and gamemode changes made by a
  console, RCON or command-block command). Those rows carry `actor_type` instead. For a
  gamemode change caused by a player's command the actor is that player (see
  `gamemode.changed`).
- **subjectId**: the player whose state was changed (permission, op and gamemode rows).
- **targetId**: another player affected (a command's target, a drop's receiver, an op target).
- **Location**: `world`, `x`, `y`, `z` (block coordinates) are promoted to the indexed
  location fields. Secondary positions use a prefix (`from_world`, ...).
- **Items**: whenever a row is about one stack, `material`, `amount`, `display_name`
  (plain text, max 64 chars), `item_uuid` and `parent_item_uuid` are included. The two
  UUIDs are read from the CoI PDC keys `circleofimagination:item_uuid` and
  `circleofimagination:item_parent`, and are left out when the stack is not tracked.
  Multi-stack rows carry a digest (see below), and the first tracked stack is promoted
  to `item_uuid`/`parent_item_uuid`.
- **Inventory digest** (`<prefix>stack_count`, `item_count`, `digest`, `items`,
  `item_uuids`, `parent_item_uuids`): `digest` is SHA-256 over every stack's
  `serializeAsBytes()`. `items` lists the first 40 stacks as `MATERIAL xN`. The UUID
  lists are comma-separated.
- No player names are recorded. The one exception is `actor_name` for console senders.
  Text values are capped at 256 characters, or 1024 for command, node and item-list
  keys. A row has at most 60 metadata keys.
- **Privacy**: `STAFF_RESTRICTED`. Command rows for chat-style commands (`msg`, `tell`,
  `w`, `r`, `me`, `say`, `mail`, `helpop`, `ac`, ...) are `CHAT_CONTENT`.

## Session lifecycle

| Event | When | Outcome | Key metadata |
| --- | --- | --- | --- |
| `doublelife.staff.admin_mode.entered` | After the snapshot is taken, the inventory cleared, entry commands run and TURBO nodes added (`/dl start`, `/dl turbo`, GUI) | `COMMITTED`; risk `HIGH` for TURBO | `mode`, `trigger` (`command`/`gui`), `max_minutes`, `entry_commands`, `granted_nodes`, `lp_user_loaded` (TURBO only), `gamemode`, start location, `snapshot_*` digest of the saved inventory (storage, armour, off-hand) |
| `doublelife.staff.admin_mode.exited` | After the snapshot restore, playerdata save, node/op removal and report | `COMMITTED`, or `FAILED` with reason `restore_failed` | `reason` (`manual`, `gui`, `expired`, `offline_expiry`, `quit`, `shutdown`), `player_online`, `duration_s`, `extension_min`, `activity_count`, `restore_ok`, `perms_removed`, `lp_user_loaded` (TURBO only; absent if the removal threw first), `deop_applied`, `risk_level`, `risk_score`, `gamemode_before`, location **before** the teleport back, `before_*` digest (inventory discarded by the restore), `after_*` digest (restored inventory) |
| `doublelife.staff.admin_mode.resumed` | Join after a restart when a saved session exists | `COMMITTED` for `resumed`; `CANCELLED` for `expired` / `skipped_active`; `FAILED` for `failed` | `outcome`, `reapplied_nodes`, `elapsed_min`, `inventory_cleared=false`, `restored_activity_count`, location |
| `doublelife.staff.admin_mode.quit_during_session` | `PlayerQuitEvent` (LOWEST) while a session is active, before the session is ended | `OBSERVED`, risk `HIGH` | `gamemode`, `minutes_remaining`, location, digest of the full inventory at quit. An `exited` row with `reason=quit` follows |
| `doublelife.staff.admin_mode.extended` | `/dl prolong` | `COMMITTED`, or `DENIED` with reason `cap_exceeded` (would go over 2x the base duration) | `added_min`, `new_total_min`, `lp_expiry_updated=false` (node expiry is not extended) |

## Permissions and op

| Event | When | Outcome | Key metadata |
| --- | --- | --- | --- |
| `doublelife.staff.permission.granted` | One row per configured `temporary-permissions` entry at TURBO start/resume, after LuckPerms `saveUser` completes | `COMMITTED` when saved; `FAILED` when the save failed or the LuckPerms user was not loaded (reason `lp_user_not_loaded`); `ATTEMPTED` with reason `pending` if the save was still running after 5 s during shutdown | `actor_type=system`, `action=grant`, `node` (key without `-`), `value` (`false` for a `-node` negation), `expiry` (ISO instant), `lp_result` (LuckPerms `DataMutateResult`) |
| `doublelife.staff.permission.revoked` | Same, at TURBO session end | as above | `action=revoke`, `node`, `value`, `lp_result` (`removed` if the node was present, else `absent`) |
| `doublelife.staff.op.revoked` | `setOp(false)` at TURBO session end (`reason=session_end`) or by the op whitelist (`reason=whitelist`) | `COMMITTED` if the player was op, else `OBSERVED` | `reason`, `was_op`, location; correlated to the session when one is active |
| `doublelife.staff.op.blocked` | `/op <name>` (including `/minecraft:op`) cancelled because the target is not whitelisted | `DENIED`, reason `not_whitelisted` | `target_name`, `command`; `targetId` when the name resolves to an online or cached player |

## In-session activity

| Event | When | Outcome | Key metadata |
| --- | --- | --- | --- |
| `doublelife.staff.admin_mode.action` | Every command typed by a session player (MONITOR, cancelled commands included), and every console-dispatched entry command | Typed: `ATTEMPTED`, or `DENIED` if cancelled. Entry command: `OBSERVED`, or `FAILED` if dispatch returned false | `kind=command`, `dispatcher` (`player`/`console`), `command` (raw), `command_name`, `cancelled`, `sensitive`, location. `targetId` is the first of the first four arguments that names another online player. Risk `HIGH` when `sensitive` is true: `give`, `lp`, `op`, `co`, `tp`, `gamemode`, `stop`, `invsee`, ... or a match against `risk.sensitive-commands` |
| `doublelife.staff.admin_mode.item_out` | Items leaving the session inventory (see kinds below) | `OBSERVED`; `ATTEMPTED` for `give_command` | `kind`, item fields, `container_type`, `owner_uuid` (as for `container_opened`), `delivered` |
| `doublelife.staff.admin_mode.item_in` | Items entering the session inventory: `pickup`, `armor_stand_take` | `OBSERVED` | item fields |
| `doublelife.staff.admin_mode.container_opened` | Opening any storage inventory: containers, ender chest, entity storage, plugin virtual inventories, another player's inventory | `OBSERVED` | `inventory_type`, `holder_type`, `size`, `owner_uuid` + `owner_source` (`player`: another player's inventory or ender chest; `lands_claim`: owner of the Lands claim a placed container stands in, when that is not the viewer), `claim_owner_uuid`, `claim_land_ulid`, `claim_viewer_trusted` (inside a claim), `locked` (vanilla-lockable containers), container location |
| `doublelife.staff.admin_mode.teleport` | Teleport with cause `COMMAND`, `PLUGIN` or `SPECTATE` (pearls and chorus fruit are skipped) | `OBSERVED` | `cause`, `from_world/x/y/z`, destination as `world/x/y/z`, `nearest_player_uuid` / `nearest_player_distance` (also `targetId`) |
| `doublelife.staff.admin_mode.blocks` | Block place/break, batched (see below) | `OBSERVED` | `kind` (`place`/`break`), batch fields |
| `doublelife.staff.gamemode.changed` | Any gamemode change of a session player or a staff member (`doublelife.use`, or a member of a `group-commands` group), in or out of a session | `OBSERVED`; risk `HIGH` when switching to creative | `from`, `to`, `cause`, `in_session`, `actor_type` (`entry_command`, `session_restore`, or the Bukkit cause). For cause `COMMAND`, and for cause `PLUGIN` when a command was dispatched in the same tick (plugin commands such as EssentialsX `/gm`): `source_dispatcher` (`player`, `console`, `rcon`, `command_block`, `entity`, `other`, or `unknown` when no command was dispatched in the same tick), `source_command_name`, `source_session_id` (the issuer's session). Bukkit does not name the issuer, so the last command dispatched in the same tick is used; actorId is that player (empty for console, RCON and command blocks). DoubleLife's own changes have no actorId; any other cause (including `PLUGIN` with no command in that tick) uses the player |

### `item_out` kinds

| Kind | Source |
| --- | --- |
| `container_put` / `container_take` | `InventoryClickEvent` (place, pickup, swap, shift-click, hotbar swap, bundle actions, collect-to-cursor) and `InventoryDragEvent` (items dragged into the top inventory) when the top inventory is storage. Storage means anything except crafting, creative, workbench, anvil, enchanting, grindstone, stonecutter, cartography, loom, smithing and merchant views, plus another player's inventory (invsee). Ender chests count as storage. Cancelled clicks (plugin menus) are ignored |
| `creative_spawn` | `InventoryCreativeEvent` slot writes (palette spawns, rearranging in the creative inventory) and `CLONE_STACK` middle-clicks. A tracked `item_uuid` is reported once per session |
| `drop` | `PlayerDropItemEvent`. `delivered` is true only if the Item entity is still valid and not dead one tick later |
| `drop_picked_up` | A delivered session drop picked up by another entity (`targetId` = receiving player) or collected by a hopper (`receiver_kind`). The last 2048 session drops are remembered in memory |
| `frame_put` | Item placed into an empty item frame, confirmed one tick later |
| `armor_stand_put` | `PlayerArmorStandManipulateEvent` giving an item to the stand |
| `give_command` | `/give` (any namespace or alias) naming another online player, or a selector other than `@s`. Carries `target_arg`, `material` and `amount` as typed; the item UUID is unknown |

### Batching

Rows are never written per click or per block for untracked items. Moves of stacks
without a CoI `item_uuid`, all block place/break events and creative slot writes are
added to a batch keyed by session, event, kind and container. A batch becomes one row
10 seconds after it started. Batches are checked every 5 seconds, and all of a
session's batches are flushed before its `exited` row (and at shutdown). Batch rows
carry `batched=true`, `materials` (`MATERIAL xN`, up to 48 materials, the rest as
`OTHER`), `display_names` (`MATERIAL "name" xN` for stacks with a custom name, up to 24, the
rest as `OTHER_NAMED`; absent when none was named), `total_amount`, `event_count`, `window_start_ms`, `window_end_ms`, the first
position as `world/x/y/z`, the bounding box `min_*`/`max_*`, and for drops
`delivered_amount`/`undelivered_amount`. A stack **with** an `item_uuid` always gets
its own row with the full item fields.

## Outside a session

| Event | When | Outcome | Key metadata |
| --- | --- | --- | --- |
| `doublelife.staff.command.restricted_blocked` | A `group-commands` command cancelled for a staff member outside a session | `DENIED`, reason `session_required` | `command_name`, `command`, `group`, location |
| `doublelife.system.config.reloaded` | `/dl reload` after the new config is loaded | `COMMITTED` | `actor_type`, `op_whitelist_enabled`, `temporary_permissions`, `entry_commands`, `max_minutes` |

## Behaviour notes tied to the rows

- **Staff activity log, blocks**: the in-memory log (text log, Discord/callback report,
  risk scoring) batches block place and break events separately, records the block type
  at the moment of the event (broken blocks used to show as `AIR`), and logs the blocks
  still batched when the session ends instead of dropping them.

- **Quit**: a session now ends at `PlayerQuitEvent` (LOWEST priority), before the
  server saves the player. The snapshot is restored, playerdata saved, TURBO nodes
  removed and op cleared while LuckPerms still has the user loaded.
- **Shutdown**: every online session is ended synchronously and independently, so one
  failure does not skip the rest. During disable the reporter writes only the log
  file and the flagged-session console warning; Bukkit refuses async tasks at that
  point, so Discord, callback and AI calls are skipped. Only sessions whose snapshot
  could not be restored are saved for resume. LuckPerms saves are awaited for up to
  5 s so their rows are emitted before the audit client closes.
- **Negations**: a `-node` entry in `temporary-permissions` is added as a real
  LuckPerms negation (`node=false`, with expiry). Removal clears the literal key (as
  before, which also removes `-node` literal keys left by older versions) and the
  temporary `node=false` node, but never a permanent grant of `node`.
- **Restricted commands**: the typed label is lower-cased and its namespace stripped.
  It is then resolved through the server command map (command name, label and
  aliases, plus the vanilla `tp`/`teleport` redirect) before it is matched against
  `group-commands`.
