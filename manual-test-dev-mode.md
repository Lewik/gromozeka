# Manual Test for DEV Mode

## Test Setup

1. Set environment variable: `export GROMOZEKA_MODE=dev`
2. Run Gromozeka: `./gradlew :bot:run`
3. Check console logs for DEV mode indicators

## Expected Console Output

Should see these messages:
```
[SettingsService] Starting application in DEV mode...
[ClaudeCodeStreamingWrapper] DEV mode detected - loading additional DEV prompt
[ClaudeCodeStreamingWrapper] DEV prompt loaded successfully (XXX chars)
```

## Window Title Test

Window title should show: `🤖 Громозека [DEV]`

## Chat Behavior Tests

### Test Command Response
1. Type: `тест`
2. Expected: Should respond with a tongue-twister or short phrase like:
   - "Карл у Клары украл кораллы!"
   - "All systems green, ready to hack!"
   - "Four arms, infinite possibilities!"

### Error Reporting Test  
1. Try to trigger an error (e.g., invalid operation)
2. Expected: Should report errors with full details instead of hiding them

### Development Context Test
1. Ask about internal processing
2. Expected: Should be more verbose about what it's doing internally

## Comparison with PROD Mode

Run same tests with `GROMOZEKA_MODE=prod` or default:
- Window title should NOT show `[DEV]`
- "тест" command should behave normally (not trigger special responses)
- Less verbose error reporting

## Success Criteria

✅ DEV mode properly detected from environment variable  
✅ DEV prompt successfully loaded and appended  
✅ Window title shows [DEV] indicator  
✅ Test commands work as specified  
✅ More verbose error reporting  
✅ PROD mode works normally without DEV features