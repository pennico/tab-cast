#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Writes dist/Open-on-TV.shortcut: an Apple Shortcut for the Share menu (iPhone, iPad, Mac) that sends the shared
page to Tab Cast TV (POST /api/open with the X-TabCast-Token header).

The TV address and the token are import questions (asked when the shortcut is added), so the file holds no secret.
Shortcut files must be signed before iOS/macOS import them; on the Mac:
    shortcuts sign --mode anyone --input Open-on-TV.shortcut --output "Open on TV.shortcut"
"""
import os
import plistlib
import uuid

def text(s, attachments=None):
    v = {"string": s}
    if attachments:
        v["attachmentsByRange"] = attachments
    return {"Value": v, "WFSerializationType": "WFTextTokenString"}

def output_of(action_uuid):
    return {"{0, 1}": {"OutputName": "Text", "OutputUUID": action_uuid, "Type": "ActionOutput"}}

addr, token, call, note = (str(uuid.uuid4()).upper() for _ in range(4))
OBJ = "￼"  # the "variable goes here" character of a text token

actions = [
    {"WFWorkflowActionIdentifier": "is.workflow.actions.gettext",
     "WFWorkflowActionParameters": {"UUID": addr, "WFTextActionText": "http://TV-ADDRESS:46897"}},
    {"WFWorkflowActionIdentifier": "is.workflow.actions.gettext",
     "WFWorkflowActionParameters": {"UUID": token, "WFTextActionText": "paste-the-token-here"}},
    {"WFWorkflowActionIdentifier": "is.workflow.actions.downloadurl",
     "WFWorkflowActionParameters": {
         "UUID": call,
         "WFURL": text(OBJ + "/api/open", output_of(addr)),
         "WFHTTPMethod": "POST",
         "ShowHeaders": True,
         "WFHTTPHeaders": {"Value": {"WFDictionaryFieldValueItems": [
             {"WFItemType": 0, "WFKey": text("X-TabCast-Token"), "WFValue": text(OBJ, output_of(token))}]},
             "WFSerializationType": "WFDictionaryFieldValue"},
         "WFHTTPBodyType": "JSON",
         "WFJSONValues": {"Value": {"WFDictionaryFieldValueItems": [
             {"WFItemType": 0, "WFKey": text("url"), "WFValue": text(OBJ, {"{0, 1}": {"Type": "ExtensionInput"}})}]},
             "WFSerializationType": "WFDictionaryFieldValue"},
     }},
    {"WFWorkflowActionIdentifier": "is.workflow.actions.notification",
     "WFWorkflowActionParameters": {"UUID": note, "WFNotificationActionTitle": "Tab Cast TV",
                                    "WFNotificationActionBody": "Opening on the TV"}},
]

workflow = {
    "WFWorkflowMinimumClientVersion": 900,
    "WFWorkflowMinimumClientVersionString": "900",
    "WFWorkflowClientVersion": "2302.0.4",
    "WFWorkflowIcon": {"WFWorkflowIconStartColor": 463140863, "WFWorkflowIconGlyphNumber": 59511},
    "WFWorkflowTypes": ["ActionExtension"],
    "WFWorkflowInputContentItemClasses": ["WFURLContentItem", "WFSafariWebPageContentItem", "WFStringContentItem"],
    "WFWorkflowHasShortcutInputVariables": True,
    "WFWorkflowImportQuestions": [
        {"ActionIndex": 0, "Category": "Parameter", "ParameterKey": "WFTextActionText",
         "DefaultValue": "http://TV-ADDRESS:46897",
         "Text": "Tab Cast TV's address (the TV's waiting screen shows it)"},
        {"ActionIndex": 1, "Category": "Parameter", "ParameterKey": "WFTextActionText", "DefaultValue": "",
         "Text": "The token: web remote > Open on TV from other apps > Make a token for a Shortcut"},
    ],
    "WFWorkflowActions": actions,
}

out = os.path.join(os.path.dirname(__file__), "..", "dist", "Open-on-TV.shortcut")
os.makedirs(os.path.dirname(out), exist_ok=True)
with open(out, "wb") as f:
    plistlib.dump(workflow, f, fmt=plistlib.FMT_BINARY)
print("wrote", os.path.normpath(out), os.path.getsize(out), "bytes (unsigned: sign it on the Mac, see the docstring)")
