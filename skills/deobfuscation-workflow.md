# Deobfuscation Workflow Skill

Systematic deobfuscation pipeline for Java binaries with automated and manual techniques.

## Prerequisites
- Recaf MCP workspace open with an obfuscated binary
- Initial triage completed (know the obfuscation level and type)

## Pipeline

### Phase 1: IDENTIFY — Assess Obfuscation
- `search-strings` — look for encrypted/encoded strings, configuration markers
- `class-list` with no filter + small limit — check naming patterns (a.b.c, single letters = obfuscated)
- `decompile-class` on a sample — check for:
  - String encryption (method calls wrapping string literals)
  - Control flow flattening (switch-based dispatch)
  - Dead code insertion
  - Reflection-based access
  - Number/constant encoding

### Phase 2: SELECT — Choose Transformations
- `transform-list` — see available automated transformers
- Match identified obfuscation types to available transforms:
  - String encryption → string decryption transforms
  - Dead code → dead code removal
  - Control flow → control flow simplification
  - Number encoding → constant folding

### Phase 3: PREVIEW — Test on Sample
- `transform-preview` on a known obfuscated class
- Review the diff to ensure the transform helps without breaking code
- If multiple transforms needed, plan the order (string decryption first, then cleanup)

### Phase 4: APPLY — Run Transformations
- `transform-apply` or `transform-apply-batch` for automated transforms
- Apply in order: structural transforms first, then naming transforms
- Check for errors after each transform

### Phase 5: VERIFY — Check Results
- `decompile-class` on previously obfuscated classes
- Compare readability improvement
- `search-strings` — check if encrypted strings are now readable
- `decompile-diff` against pre-transform decompilation

### Phase 6: CLEAN UP — Manual Improvement
- `rename-class` / `rename-method` / `rename-field` for remaining obfuscated names
- Use context clues from decompiled code to choose meaningful names
- `comment-set` to document remaining unclear areas
- `mapping-export` to save all renaming progress

## Obfuscation Patterns Cheat Sheet

| Pattern | Indicator | Approach |
|---------|-----------|----------|
| Identifier obfuscation | Single-letter class/method names | Rename based on usage context |
| String encryption | Static method calls wrapping strings | String decryption transform |
| Control flow flattening | Large switch statements, goto loops | CF simplification transform |
| Dead code | Unreachable branches, unused methods | Dead code removal |
| Reflection | `Class.forName()`, `Method.invoke()` | Manual analysis of reflected targets |
| Resource encryption | Encrypted assets/configs | Custom decryption analysis |
