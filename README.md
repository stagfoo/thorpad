# thorpad

On-screen controls drawn over any Android game, driven by a gamepad. Built for
the AYN Thor. **No root.**

Put a control where you would tap, bind a button to it, and that button taps
there.

## How it works

Two halves, each with its own obstacle, and each solved by a different half of
one accessibility service.

**Seeing the gamepad while a game is in front.** Android delivers key events to
the *focused* window, so a background app sees nothing. An accessibility service
with `canRequestFilterKeyEvents` gets a global hook that sits above the focused
window — and returning false from `onKeyEvent` hands the key straight on. Only
buttons bound to a control are consumed; back, volume and everything else behave
exactly as they did before the app was installed.

The obvious alternative is an overlay window that holds focus. That works, and
it is what several mappers do, but a focused overlay receives *every* key
including back and volume, with nowhere to forward them because the game no
longer has focus. The key hook has none of that cost, so the overlay here is
purely visual and never takes focus.

**Putting a touch into the game.** `dispatchGesture` is the only route without
root. It is not an injected event: you describe a stroke with a duration and the
system plays it out. A tap is one short stroke. A hold is a chain — each segment
declares `willContinue` and the next begins when the previous reports finished —
because the API has no "press and stay pressed".

## Setup

1. Settings → Accessibility → **thorpad** → on.
2. Allow **draw over other apps**.
3. **START CONTROLS**, then add a button or a stick.
4. Tap the big button to flip between **LIVE** and **EDITING**. While editing,
   on the overlay: tap a control and press the gamepad button you want to bind
   it, drag it to move it, **DONE** when finished.

Binding happens on the overlay rather than in the app, because the controls are
placed over the game — that is where you are looking when you decide what a
button should do.

### There are two states and it always says which

Editing and live look nearly identical from across a game, and in editing the
overlay has to accept touches so controls can be dragged — which means it also
catches the taps meant for the game. A button that does nothing then looks
exactly like a button that is not bound.

So the state is said three times over: the app's one button carries it, the
notification carries it (and can flip it without leaving the game), and the
overlay itself shows a small **LIVE** badge or a full editing banner.

**Markers** hides the control circles and keeps the crosshair, because the
crosshair is the thing you are actually looking at while aiming and the circles
are only a reference. Buttons keep working either way, and a press still flashes
so it is visible that it landed.

**Buttons do not reach the game while editing**, and that is not a bug you can
guess: in edit mode the overlay has to accept touches so controls can be
dragged, which means it also catches the taps meant for the game. Edit mode now
says so across the top and carries its own DONE button, so you never have to go
back to the app to leave it.

## It shows its own working

Every previous attempt at this failed silently somewhere in a chain nobody could
see. Here each stage proves itself on screen:

- **The overlay appears** → the window went up and the permission is real.
- **A control lights up yellow** → the button arrived and was matched.
- **Only then** is there any question about whether the tap landed.

The setup screen also counts keys seen, taps sent, taps completed and taps
refused, so a failure says which stage it is at rather than nothing at all.

## Layout

```
Layout.kt           controls, bindings, positions — pure, 13 unit tests
TapService.kt       the key hook, and taps and holds via dispatchGesture
OverlayService.kt   owns the window, matches buttons to controls
OverlayView.kt      draws the controls, drags them in edit mode
MainActivity.kt     permissions, adding and binding
```

```
AimEngine.kt        stick to dragged finger — written and tested, not yet wired
AimSettings.kt      the numbers behind it
```

Neither `Layout` nor `AimEngine` has any Android types in it, so the rules —
one button cannot drive two controls, a control cannot be dragged off screen, a
sweep covers most of the screen before it hitches — are tested without a device.
30 tests.

## Two ways to use a stick

Which one a game understands is not something that can be settled from outside
it, so both are here.

**Stick (drag)** pulls a finger around the screen, which is what a game that
tracks a travelling touch reads for aiming. The dashed rectangle on the overlay
is where the finger may go.

**Stick (crosshair)** injects nothing at all. It moves a marker, and a button
set to **✛ on** taps wherever that marker is. Far less to ask of a game — no
stroke to run out of, no region edge, no recentring hitch — but it only helps if
the game acts on a tap where you put it rather than on a finger travelling.

### Reading a stick at all

Analog axes are motion events, not key events, and the two arrive by completely
different routes.

On **Android 14 and up** an accessibility service can ask the system for motion
events: no window, no focus, no cost.

**Below 14** it cannot see them at all, and the only thing that can is a window
holding focus. So the overlay takes focus, but only when a stick control exists
— and because a focused overlay receives *every* key, back, home, recents and
volume are performed outright rather than swallowed. It is still a trade, just a
much smaller one than losing those buttons.

The setup screen prints the Android version, which route is in use, and a live
count of stick events arriving.

The thing sticks exist to solve is that a drag has an end: when the finger reaches
the edge of its region it must lift, jump back and press again, and that hitch
is what you feel on a long sweep. Two settings decide how often it happens:

| region | stroke starts | longest stroke | hitches per 3s sweep |
|---|---|---|---|
| full screen | far edge | **0.880 screens** | 7 |
| full screen | centre | 0.477 | 13 |
| 40% box | centre | 0.165 | 35 |

Measured, not guessed — `AimEngineTest."travel table"` prints exactly that, so
it cannot drift from the code. Starting each stroke against the *far* side of
the region, facing the way the stick points, is worth as much as making the
region bigger.

It is deliberately not switched on. Buttons are the part simple enough to
verify, and three previous attempts failed by stacking unverified layers.
