# Permissions

All permissions are standard Bukkit permissions and work with LuckPerms or any other permission plugin. LuckPerms is not required.

| Permission | Default | Purpose |
| --- | --- | --- |
| `qscrimescenes.police` | op | Use the police evidence shovel on a crime scene chest (read report, archive and remove evidence). |
| `qscrimescenes.police.give` | op | Give the police evidence shovel with `/qsds tool give`. |
| `qsds.hospital` | op | Parent node for hospital management. |
| `qsds.hospital.set` | op | Set the hospital operating-room spawn. |
| `qsds.hospital.teleport` | op | Teleport to the hospital (administrative testing). |
| `qsds.hospital.info` | op | View hospital configuration and validity. |
| `qsds.evidence.list` | op | List evidence and archives. |
| `qsds.evidence.info` | op | Inspect an individual evidence record. |
| `qsds.evidence.recover` | op | Recover an archived inventory. |
| `qsds.reload` | op | Reload configuration and messages. |
| `qsds.status` | op | View plugin/database status. |
| `qsds.debug` | op | Diagnostic commands (`simulate`, `reconcile`). |
| `qsds.admin` | op | Convenience parent granting all qS Death System nodes above. |

## Recommended groups

**Police**

```
qscrimescenes.police = true
```

**Police supervisor / evidence clerk**

```
qscrimescenes.police = true
qscrimescenes.police.give = true
qsds.evidence.list = true
qsds.evidence.info = true
qsds.evidence.recover = true
```

**Administrator**

```
qsds.admin = true
```

## Notes

- Ordinary players receive no hospital command and no evidence access.
- The killer's name is never broadcast; it is only in the private report and in `qsds.evidence.info` for staff.
- Display names and lore are never used for authorization; the tool is validated by persistent data and a per-installation token.
