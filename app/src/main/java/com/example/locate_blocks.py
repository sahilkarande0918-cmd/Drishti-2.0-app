import os

filepath = r"c:\Users\Sahil\OneDrive\Desktop\Drishti 2.0 APP\drishti-2.0-app\app\src\main\java\com\example\ui\viewmodel\DrishtiViewModel.kt"

targets = [
    'command.startsWith("navigate") || command.contains("navigate to")',
    'command.contains("remember this as") || command.contains("save landmark")',
    'val isNavIntent = command.contains("go to")'
]

with open(filepath, "r", encoding="utf-8") as f:
    lines = f.readlines()

for target in targets:
    for idx, line in enumerate(lines):
        if target in line:
            print(f"Found '{target}' at line {idx+1}")
