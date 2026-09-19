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

The live overlay is also drawn at 80% window alpha. Android 12 blocks touches
that pass beneath an overlay owned by another app when that overlay is more
opaque than that — and it judges the window, not what was painted into it, so a
fully transparent window at full alpha silently eats every finger aimed at the
game.

**Buttons do not reach the game while editing**, and that is not a bug you can
guess: in edit mode the overlay has to accept touches so controls can be
dragged, which means it also catches the taps meant for the game. Edit mode now
says so across the top and carries its own DONE button, so you never have to go
back to the app to leave it.

## Hold: a touch that lasts as long as the button

A control set to **Hold** presses when the button goes down and releases when it
comes up, rather than tapping for a fixed length. That is what a game wants when
holding does something and letting go does something else — hold to leave cover
and shoot, release to drop back in.

Two things make the difference between that working and nearly working:

**The overlay ducks for the whole hold, not for a moment.** If ducking is what
makes the touch land at all, restoring the overlay part-way through puts it back
over a finger that is still down and the hold ends there. It now stays out of
the way until the button is released, and counts holds so two at once do not
surface it under each other.

**A held finger drifts a pixel or two.** A hold that never moves emits a press
and then nothing at all, and some engines read that as a finger present but idle
and stop acting on it — so holding to keep shooting quietly stops shooting. The
drift is movement without dragging anything, and is adjustable (0 / 2 / 5 / 10px)
because how much counts as still moving is the game's decision.

The hold also restarts its own stroke chain if the system cancels a segment.
Taking a cancellation as the end of the hold means a button held down goes quiet
for no visible reason.

## When a tap arrives and the button ignores it

Two causes, both invisible from the outside, so both are adjustable rather than
guessed at.

**Tap length.** A game reads touches once a frame. A tap shorter than two frames
can have its press and release land inside the same one, so the engine sees a
finger appear and vanish with no press in between — the touch registers plainly
(a ripple, a glow) and the button under it does nothing. At 30fps a frame is
33ms, which is why the original 60ms was never a safe number. Default is now
140ms, cycling 70 / 140 / 240 / 400.

**Obscured touches.** Android marks a touch as obscured when another app's
window sits above the point it landed on, and a view can be set to refuse
obscured touches outright. Whether a *not-touchable* overlay counts as obscuring
is not something to be confident about from the outside, so **Overlay ducks
while tapping** settles it by shrinking the window to a pixel for the length of
the tap. If buttons start working with it on, that was the cause.

## The mascot

There is a vtuber in the corner. She runs the tutorial the first time the app
opens — one step at a time, tapped through, because each step is a thing to go
and do and a tutorial read all at once is finished before any of it is done.
**Show me how** runs it again.

After that she comments when a setting changes, saying what the change *means*
rather than that it happened: the button already relabelled itself, so repeating
it would be noise with a face on. Turning ducking on gets "if that fixes a dead
button, the overlay was obscuring it"; taking focus for the sticks gets a
warning that the game will probably mute.

She keeps quiet during her own tutorial — interrupting an explanation to remark
on a setting loses whichever the reader was part-way through.

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

**Stick (crosshair)** injects nothing on its own. It moves a marker, and a
button set to **✛ on** touches wherever that marker is. Far less to ask of a
game — no stroke to run out of, no region edge, no recentring hitch.

**A button held at the crosshair follows it.** That is what makes the crosshair
worth having rather than a novelty: in a game where holding zooms and dragging
aims, one finger has to do both. A hold that read the crosshair once at press
time would zoom and then refuse to look around.

**Crosshair speed** (0.8–4.5 screens a second) and **size** (small to huge) are
both adjustable, because neither is knowable from outside the game you are
pointing it at.

### Reading a stick at all

Analog axes are motion events, not key events, and they arrive by a completely
different route from buttons. Three routes exist, none available everywhere, and
thorpad takes the best one going:

| Route | Available | Cost to the game |
|---|---|---|
| System motion events | Android 14+ | none |
| Shizuku reading `/dev/input` | needs Shizuku started | none |
| A focused overlay | always | **the game usually mutes and pauses** |

The last one is a genuinely poor option and is off unless switched on. A window
holding focus can see a stick, but a game that *loses* window focus commonly
mutes and stops responding — so the overlay going up can look exactly like the
app breaking.

Shizuku is the one to use below Android 14. It runs a reader of ours as the
shell uid, which is in the `input` group, and reads the stick straight off the
kernel — where focus is not a concept that exists. Buttons need none of this;
they come through a global key hook that costs the game nothing, so a
button-only layout works everywhere with no setup at all.

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
