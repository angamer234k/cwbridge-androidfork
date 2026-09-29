Follow the TODO.MD if no user objective is given.
Always append "[skip ci]" before a commit name if the change is not critical and does not need CI building.
If the user is unsure what this repo is about, explore the README.MD.

The app's MAIN code is in `app/src/main/java/com/cwbridge/android` (all one path, /com/cwbridge/android exists as one folder with that name. No but's.), the helper is in `helper/src/main/java/com`. Theres 2 folders, `cwbridge/helper` is the main helper app's code.

# RULES

- If something works, dont touch it. If something doesnt work, fix it.
- Try to avoid useless tasks like editing a file just to remove a comment. Either do it in bulk or just skip the step altogether.
- Do not edit the ADB over OTG code. It works just fine (atleast for now, 29/09/2026)
