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
3. **Show**, then **Add a control**, then **Bind** and press the button you want.
4. **Edit** to drag controls where you want them; **Play** to make the overlay
   invisible to touch so your fingers reach the game.

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

## Not wired up yet: stick aiming

`AimEngine.kt` is here and tested, but nothing calls it. It turns stick
deflection into a dragged finger, which is what a game like NIKKE actually reads
for aiming — there is no button to bind, only a drag.

The thing it exists to solve is that a drag has an end: when the finger reaches
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
