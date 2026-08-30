# Frequently Asked Questions

If you didn't find an answer to your question, ask [in issues](https://gitlab.com/xynngh/YetAnotherCallBlocker/-/issues).


## The app doesn't prevent a short sound before a call is blocked.

["Advanced call blocking mode"](FAQ.md#whats-that-advanced-call-blocking-mode) eliminates this problem.

Enabling "monitoring service" also seems to help with this.


## Do I have to set Yet Another Call Blocker as the default "Phone app"?

Not necessarily. See ["Advanced call blocking mode"](FAQ.md#whats-that-advanced-call-blocking-mode).


## Calls aren't blocked, I don't get any informational notifications

Check that you've granted all the requested permissions (the app asks for missing permissions when you open its main screen).

The app may encounter troubles providing its features on stock firmwares by some manufacturers (like MIUI from Xiaomi). There are two known issues so far:

* [Call blocking and informational notifications don't work on MIUI (stock firmware on Xiaomi phones)](https://gitlab.com/xynngh/YetAnotherCallBlocker/-/issues/12).  
  This problem can be solved by enabling an always-running "monitoring service" (in Yet Another Call Blocker settings). Android requires to display a notification for a service like that, but on Android 8+ you may disable the notification using "notification channels". This feature has no effect on battery life.  
  Alternatively (or additionally) you may enable the ["Advanced call blocking mode"](FAQ.md#whats-that-advanced-call-blocking-mode) (in Yet Another Call Blocker settings). The feature should help to fix call blocking, though still won't help to provide the informational notifications (except "Call blocked" notifications - these will work fine).
* [No informational notifications on some modern Samsung phones](https://gitlab.com/xynngh/YetAnotherCallBlocker/-/issues/13).  
  The cause of this problem is unknown (so far) and there's no workaround. Provide system logs (not app logs - these show no anomalies) if you want this fixed.


## The app doesn't have a persistent notification. Does it work?

Yet Another Call Blocker doesn't have a permanent notification since it doesn't have any always-running services. The only actions it may do in background are optional auto-updates and incoming call handling (which are limited to the duration of corresponding events). So yeah, it does work ([unless it doesn't](FAQ.md#calls-arent-blocked-i-dont-get-any-informational-notifications)).


## Is there a whitelist? How can I allow a particular number with negative rating to call me?

Since [contacts are never blocked](FAQ.md#how-do-blocking-options-work-exactly) (you need to enable "Use contacts" option), you can simply add that specific number to your contacts, and it will be able to call you.  
Currently, there's no whitelist feature, but there are plans to eventually implement it. Vote for or discuss it in [#11](https://gitlab.com/xynngh/YetAnotherCallBlocker/-/issues/11) and [#26](https://gitlab.com/xynngh/YetAnotherCallBlocker/-/issues/26).


## Can I block all numbers not present in Contacts?

There's no dedicated option [yet](https://gitlab.com/xynngh/YetAnotherCallBlocker/-/issues/31), but there is a way to achieve the effect: enable "Use contacts" option and create a blacklist pattern matching any number (`*`). The app [never blocks contacts](FAQ.md#how-do-blocking-options-work-exactly), but all unfamiliar numbers will be blocked by this pattern. You will also need to enable "Block hidden numbers" option to have hidden numbers blocked.

Additionally, modern Android versions have "Do not disturb" mode which can be customized to block unfamiliar numbers.


## What's that "Advanced call blocking mode"?

"Advanced call blocking mode" is a mode that uses a modern call blocking method ([CallScreeningService](https://developer.android.com/reference/android/telecom/CallScreeningService)-based) which allows to block calls immediately before the phone starts ringing ("normal" mode can't handle incoming calls fast enough, so your phone may ring for a very short period of time before the call is finally blocked).  
For this feature to work the app must be set as a "Phone app" (Android 7–9) or a "Caller ID app" (Android 10+). The feature is not available on older Android versions. This feature has no effect on battery life.  
**Important**: the app doesn't provide/replace any in-call UI - call handling is delegated to your pre-installed Dialer app (or the "Phone app" selected by you on Android 10+), which is actually used to manage a call.  
Obviously, on Android 7–9 you can't enable this feature **and** select a third-party Dialer app. This is a restriction of Android, I'm not aware of any way to work around it.


## I don't want to see some of the informational notifications, can I disable them? Can I change notification priorities?

If you don't want to receive some notifications (like notifications for calls from your contacts), you should use Android's [notification channels](https://www.androidcentral.com/notification-channels) feature to disable particular notification types or change their priorities. Yet Another Call Blocker provides plenty of notification channels for you to customize.  
On pre-Android 8 devices there's a couple of notification-related options in the settings.

There's also an option to disable all the informational notifications at once.


## What is "block forged numbers"?

Some networks sign the number a call is placed from, so that the phone can tell whether the
number shown is really the caller's (this is called STIR/SHAKEN). Android passes that verdict on
since Android 11, and the app can block or silence a call the network reports as forged - which
catches a spoofed number no matter what the database says about it, since the number on such a
call is one the caller doesn't own.

It only works where the network does it. In much of the world, including Germany, it isn't in
use: every call is then simply "not verified", which is not the same as "forged", and the option
does nothing. Spoofing is dealt with by the networks themselves there.

Like every other blocking option, this one never blocks contacts. It needs
["Advanced call blocking mode"](FAQ.md#whats-that-advanced-call-blocking-mode), since the verdict
comes with the call being screened.


## Can the app silence a call instead of blocking it?

Yes: "Silence calls" in Settings lets you pick the ratings that get the ringer muted
(negative, neutral, unknown). Such a call isn't rejected - it goes through silently,
your phone app shows it as usual and it ends up in the call log, you just aren't disturbed by it.
Contacts are never silenced, and blocking wins if a number is set to be blocked as well.

The feature is provided by the call screening service, so it needs
["Advanced call blocking mode"](FAQ.md#whats-that-advanced-call-blocking-mode) and Android 10+.


## What countries are covered by the offline number database?

I'm not sure to be honest. But I believe most of the world is covered.  
You can install the app and look up some recent unwanted calls (if you had any) to see whether the app would have blocked them for you.


## How do wildcards in the blacklist work?

`*` matches zero or more digits, `#` matches exactly one digit.  
So a pattern `+123*` will match any number starting with `+123`.  
A pattern `*123` will match any number ending with `123`.  
A pattern `*123*` will match any number that contains `123`.  
A pattern `+123##` will match any 5-digit number starting with `+123` (like `+12345`).

The number format *must* match the format that Android uses, that's why the leading `+` with country code is usually important.


## How do blocking options work exactly?

1. If "Use contacts" is enabled and the number is in contacts, the call is **never blocked** (regardless of other options).  
  Extra information (if any) about the number is displayed anyway.
1. If "Block hidden numbers" is enabled and the number is hidden, the call is **blocked**.  
  Theoretically, a failure to detect number may result in a call from a contact to be blocked, but I haven't heard about it ever happening.
1. If "Block based on rating" is enabled and the number has a *negative rating*, the call is **blocked**.  
  Currently, "negative rating" means the number has more negative reviews than a sum of neutral and positive reviews.
1. If "Block blacklisted numbers" is enabled and the number matches any valid blacklist pattern, the call is **blocked**.


## How does the app display the caller name/ID during an incoming call?

There are two independent ways, both in Settings under "Caller ID":

* **"Caller ID in the phone app"** (enabled by default).  
  The app registers itself as a contacts directory, and your phone app asks it about every number
  it can't find in your contacts. The result is displayed by the phone app itself - on the incoming
  call screen, in the call log and in the contacts search - so there's no extra window and no
  additional permission. Since the info is resolved while the call is being screened
  (see ["Advanced call blocking mode"](FAQ.md#whats-that-advanced-call-blocking-mode)),
  it's ready before the phone starts ringing.  
  The stock Android phone app (AOSP Dialer, Google Phone) supports this. Some vendor phone apps
  (Samsung, MIUI) use their own lookup and may ignore it - use the overlay below in that case.
* **"Caller info overlay"** (disabled by default).  
  The app draws a small window with the caller info over the incoming call screen.
  This works regardless of the phone app, but it requires the "display over other apps" permission.

Both are only used for the numbers the app knows something about (a rating, a category,
a blacklist entry, a company name); contacts are never affected, and unknown numbers are
displayed by the phone app as usual.

## Is there a way to display an overlay/pop-up screen with caller information?

Yes, see [the question above](FAQ.md#how-does-the-app-display-the-caller-nameid-during-an-incoming-call).
The upstream discussion of the feature is in [this issue](https://gitlab.com/xynngh/YetAnotherCallBlocker/-/issues/3).


## I have "block hidden numbers" enabled, but some hidden/"private" numbers are still not blocked.

Hidden number detection is not properly standardized in Android. It took quite some effort to implement the feature as it is, but it was mostly borrowed code and guesswork.  
It probably works better in ["advanced call blocking mode"](FAQ.md#whats-that-advanced-call-blocking-mode).

If you receive a hidden call that wasn't blocked, [create a "crash report"](FAQ.md#how-to-report-a-crash-get-app-logs) and send it to me, so I can improve the feature. You can send a report even if you didn't have "block hidden numbers" enabled at the moment - the report should be just as useful.


## Can I use the app with VPN/Tor?

Partially. There's no proxy settings in the app, but system-wide tunnels should work fine. The initial database download (from gitlab) via Tor previously failed due to gitlab making extra checks, but I implemented a hack that should work for now. You can always perform the initial download (no identifiable information used) using normal internet connection. You can even avoid it by [embedding main DB](FAQ.md#the-app-takes-too-much-storage-space-what-can-i-do).

Unfortunately, the third-party servers block requests from Tor, so daily updates and online reviews are not available via Tor.


## Can I copy the database to another device or keep a backup of it?

Yes: "Manage database" (Settings -> Advanced -> Manage database) has "Export DB" and "Import DB".
The export is a zip archive of the offline database, which you can share or save anywhere;
importing it replaces the current database, so the app asks for a confirmation first.
It's meant for setting up another device (or reinstalling) without downloading the database again.

The archive contains the database only. The blacklist has its own export in the blacklist screen,
and the settings aren't included in either.


## What happens to the database when I filter it?

Filtering deletes the parts of the database you don't need, which can't be undone by filtering
again - so the app keeps a copy of the unfiltered database ("Keep the unfiltered database",
enabled by default) and filters that copy. This is what makes "Use the unfiltered database"
possible, and it means that changing the filter settings and filtering again always starts from
the complete database instead of narrowing down what is left of it.

The copy needs about as much space as the database itself. Turning the option off frees that
space, at the price of having to download the database again to get an unfiltered one back.
Filtering reports how many entries it removed, and if the filter matches everything, the copy is
dropped again since the database in use is the unfiltered one anyway.

When the database is updated, the filter is applied to the update automatically.


## The app takes too much storage space. What can I do?

Normally the app takes a little under 150 MB in total: ~20 MB for the app and ~130 MB for the data (the offline number rating database).

There are options for database filtering (in the Advanced settings) that allow to save a lot of storage space by removing data that is not applicable to your region. In many cases this feature can save around 100 MB of space without the app losing any efficiency. Carefully read the option descriptions.

You can also build the app yourself embedding the primary DB (see the optional step in [build instructions](BUILDING.md#clone-the-assets-repo-optional-step-allows-to-avoid-the-initial-db-downloading-after-installation)). This will save around 70 MB (by increasing the APK size by ~25 MB, but decreasing the data size by ~95 MB). You won't need to perform the "initial DB downloading" on first start. The downsides are that you'll eventually (once in a couple of months) have to rebuild the app with a fresh primary DB and you won't be able to update via F-Droid.


## What's the source of that "third-party crowdsourced phone number database"?

I'm not sure disclosing the source is a great idea, I didn't ask for a permission to use it after all. Finding out the source is quite easy anyway.


## Are there any plans for X feature?

Check [issues](https://gitlab.com/xynngh/YetAnotherCallBlocker/-/issues). If there's nothing about it, create a new one and ask there.


## How to report a crash / get app logs?

Sometimes reporting a sequence of steps to reproduce a problem is enough, but in most cases you need to provide extra information in the form of app logs.  
You can get app logs ([logcat](https://developer.android.com/studio/debug/am-logcat) output) right inside the app by going to "Settings -> Advanced settings" and pressing "Export logcat".  
As mentioned in the description, the logs may contain some personal information - don't post it publicly without checking.  
If you redact personal data (which you should do), please *replace* numbers (with random numbers, preferably without changing format) instead of *removing* them completely. Otherwise, it is hard to tell whether the number was missing in the app or you removed it. That is especially important when dealing with hidden numbers.


## There's plenty of other \[better looking, with more features\] Android call blocking apps around. Why should I use yours?

You don't have to. If you're happy with some other app - good for you.  
This project was started because I needed to help my non-techie relatives fight phone spam. Giving calls and contacts permissions to some proprietary app is just not an option for me.  
There's only a few FOSS (free and open source) apps that provide call blocking and none of them has any kind of a crowdsourced blacklist. So I created Yet Another Call Blocker to solve this.  
After a while the app got new features, some of which are unique on the FOSS scene (for example, I believe that the "advanced call blocking mode" is not present in any other FOSS spam blocker).
