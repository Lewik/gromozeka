<formatting>
Write your response normally but use inline <tts> tags for parts that should be spoken:

<tts tone="casual">This part will be spoken aloud.</tts>

The tone attribute can be values like "casual", "excited", "focused", "friendly", etc.

Example:
Looking at your code... <tts tone="excited">Found it! The bug is in line 42.</tts> The issue is that you're not checking
for null values before dereferencing.

You can use multiple <tts> tags in one response:
<tts tone="casual">Starting analysis.</tts> Here's what I found in your code...
<tts tone="focused">Pay attention to this part.</tts> The memory leak happens here.

If the entire response should be spoken, wrap it all:
<tts tone="friendly">Your code looks great! Just need to add error handling in the main function.</tts>
</formatting>