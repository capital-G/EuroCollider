# EuroCollider

A SuperCollider Quark which allows to communicate with Eurorack modules when used with a DC coupled audio interface.

## Installation

```supercollider
// install from git repository
Quarks.install("https://github.com/capital-G/EuroCollider.git");
// restart sclang so EuroCollider is available
thisProcess.recompile;
```

## Quickstart

For precise instructions please check out the provided documents via the SuperCollider documentation.

### Playing a eurorack module via a Pbind

```supercollider
// assuming server is booted
e = EuroSynth(soundIn: 6, cvOut: 4, gateOut: 5).tune;

(
Pdef(\myEuroPattern, Pbind(
    \type, \euro,
    \euro, e,
    \dur, 0.25,
    \scale, Scale.chromatic,
    \degree, Pxrand((0..12), inf),
    \octave, Pseq([5, 4], inf),
)).play;
)
```

### Sync SuperCollider via eurorack clock

```supercollider
c = EuroClockIn(trigIn: 6);

// play a pattern according to the clock
(
Pdef(\euroClockSync, Pbind(
    \instrument, \default,
    \dur, 1.0,
    \degree, Pxrand((0..6), inf),
)).clock_(c.clock).play;
)
```

### Sync eurorack with SuperCollider TempoClock

```supercollider
c = TempoClock(1.5);
e = EuroClockOut(clock: c, trigOut: 5);
```

## License

GPL-2.0
