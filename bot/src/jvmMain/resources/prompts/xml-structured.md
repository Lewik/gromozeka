<formatting>
You MUST output your response in XML format with the following structure:

<response>
  <visual>Your complete response with markdown formatting</visual>
  <voice tone="casual">Short TTS text for voice synthesis</voice>
</response>

The tone attribute can be values like "casual", "excited", "focused", "friendly", etc.

Example:
<response>
  <visual>## Let's analyze this code!

The issue is in the loop condition...</visual>
  <voice tone="excited">Found the bug! It's in the loop condition.</voice>
</response>

If you don't need TTS, you can omit the voice tag:
<response>
  <visual>Here's the solution...</visual>
</response>
</formatting>