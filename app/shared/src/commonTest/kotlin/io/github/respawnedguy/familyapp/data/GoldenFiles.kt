package io.github.respawnedguy.familyapp.data

/**
 * Stored files exactly as released app versions wrote them. Never edit a golden file: add a new
 * one for every new schema. FamilyJsonMigrationTest reads all of them with the current code.
 */
object GoldenFiles {
    /** Schema 1, written by app 0.2.x (main @ fe577d5, 25.11.2026) with FamilyRepository.exportBackup(). */
    val SCHEMA_1 = """
{
    "schema": 1,
    "deviceId": "id0",
    "selfId": "id1",
    "members": [
        {
            "id": "id1",
            "name": "Nils",
            "colorIndex": 0,
            "kind": "ADULT",
            "hasDevice": true,
            "stamp": {
                "wallMs": 1763600001000,
                "counter": 0,
                "device": "id0"
            }
        },
        {
            "id": "id2",
            "name": "Lisa",
            "colorIndex": 1,
            "kind": "ADULT",
            "hasDevice": false,
            "stamp": {
                "wallMs": 1763600002000,
                "counter": 0,
                "device": "id0"
            }
        },
        {
            "id": "id3",
            "name": "Mia",
            "colorIndex": 2,
            "kind": "CHILD",
            "hasDevice": false,
            "stamp": {
                "wallMs": 1763600003000,
                "counter": 0,
                "device": "id0"
            }
        }
    ],
    "items": [
        {
            "id": "id4",
            "title": "Mia braucht bis Freitag Turnschuhe",
            "source": "VOICE",
            "createdMs": 1763600004000,
            "createdBy": "id1",
            "photoPath": null,
            "status": "OPEN",
            "concerns": "id3",
            "concernsOrigin": "RULE",
            "assignee": "id2",
            "assigneeOrigin": "PERSON",
            "due": "2026-11-27",
            "dueDecided": true,
            "dueOrigin": "RULE",
            "reasons": [
                "Name „Mia“ im Text",
                "„Freitag“ → Fr 27.11."
            ],
            "doneMs": null,
            "doneOn": null,
            "inboxTaps": 2,
            "stamp": {
                "wallMs": 1763600006000,
                "counter": 0,
                "device": "id0"
            }
        },
        {
            "id": "id5",
            "title": "Foto",
            "source": "PHOTO",
            "createdMs": 1763600007000,
            "createdBy": "id1",
            "photoPath": "/data/user/0/io.github.respawnedguy.familyapp/files/photos/abc.jpg",
            "status": "IRRELEVANT",
            "concerns": null,
            "concernsOrigin": null,
            "assignee": null,
            "assigneeOrigin": null,
            "due": null,
            "dueDecided": false,
            "dueOrigin": null,
            "reasons": [],
            "doneMs": null,
            "doneOn": null,
            "inboxTaps": 1,
            "stamp": {
                "wallMs": 1763600016000,
                "counter": 0,
                "device": "id0"
            }
        },
        {
            "id": "id6",
            "title": "Zahnarzt anrufen",
            "source": "TEXT",
            "createdMs": 1763600009000,
            "createdBy": "id1",
            "photoPath": null,
            "status": "INBOX",
            "concerns": null,
            "concernsOrigin": null,
            "assignee": null,
            "assigneeOrigin": null,
            "due": null,
            "dueDecided": false,
            "dueOrigin": null,
            "reasons": [],
            "doneMs": null,
            "doneOn": null,
            "inboxTaps": 0,
            "stamp": {
                "wallMs": 1763600010000,
                "counter": 0,
                "device": "id0"
            }
        },
        {
            "id": "id7",
            "title": "Milch",
            "source": "TEXT",
            "createdMs": 1763600011000,
            "createdBy": "id1",
            "photoPath": null,
            "status": "DONE",
            "concerns": null,
            "concernsOrigin": null,
            "assignee": "id1",
            "assigneeOrigin": "PERSON",
            "due": null,
            "dueDecided": true,
            "dueOrigin": null,
            "reasons": [],
            "doneMs": 1763600014000,
            "doneOn": "2026-11-20",
            "inboxTaps": 1,
            "stamp": {
                "wallMs": 1763600015000,
                "counter": 0,
                "device": "id0"
            }
        }
    ],
    "settings": {
        "firstCaptureAction": "VOICE",
        "allowOnlineSpeech": false,
        "backupFolder": null,
        "lastBackupMs": null,
        "lastBackupError": null,
        "latency": [
            {
                "trigger": "Kachel",
                "ms": 900,
                "atMs": 1763600017000
            }
        ]
    }
}
""".trimIndent()

    val ALL = mapOf(1 to SCHEMA_1)
}
