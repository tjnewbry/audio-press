# Audio Press: Objective and Scope

## OBJECTIVE

We are going to develop a program that accepts a PDF as input. The PDF will generally be a novel or textbook. It might be a photo scan and it might contain the actual text. The program then outputs either lo or hi, depending on flags:

lo: A readable transcript of the textbook. Simple TXT, or a format that allows you to denote what text came from what page. This transcript will be fed into a tts engine later, so the goal is to trim any headers, footers, page numbers, formatting artifacts, etc.

hi: Includes the instructions of Lo mode, but also produces a map of where each word in the transcript lives on the page, so that a future application can allow users to click words on the page to start playback in that spot, or highlight the words as they are read.

## SCOPE

As mentioned above, this program will eventually be fed into a tts engine and wrapped into a larger app. All we need right now is something that strictly functions within the terminal. There are four development goals in order:

ALPHA. Text-based pdfs for input, lo output.

BRAVO. Text-based pdfs for input, hi output.

CHARLIE. Convert image-scan pdfs into text-based pdfs that are suitable for lo outputs.

DELTA. Improve the process from milestone charlie so that image-scan pdfs can produce hi outputs.

WE ARE CURRENTLY PURSUING MILESTONE ALPHA. YOU SHOULD ARCHITECT THE PROGRAM WITH FUTURE MILESTONES IN MIND, BUT DO NOT PROCEED PAST MILESTONE ALPHA UNTIL USER TJNEWBRY HAS VERIFIED THAT IT IS COMPLETE AND UPDATED THIS PARAGRAPH ACCORDINGLY.