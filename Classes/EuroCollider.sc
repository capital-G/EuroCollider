/*
TODO

* check if audio interface has DC offset
* add freq2cv(synth) to simple number?
* add cv2freq
* add check if clock stopped via a routine?
* allow to update ClockOut?

---

BUGS

* clockin sometimes does not match?
* clockout: update synth instead of respawning 2
* ensure that we do not overwrite synthdef on e.g. naming of SendReply
*/

EuroSynth {
	// private variables
	var <soundIn;
	var <cvOut;
	var <gateOut;
	var playMonitor;
	var <negativeVoltage;

	var <curFreq;
	var oscFunc;
	var <controlSynth;
	var tuner;
	var <isTuned;
	var randSeed;
	var <tuningMap;
	var tunerOscChannel;
	var tunerDefName;

	*new {|soundIn, cvOut, gateOut, playMonitor=false, negativeVoltage=false|
		^super.newCopyArgs(
			soundIn,
			cvOut,
			gateOut,
			playMonitor,
			negativeVoltage,
		).init;
	}

	*initClass {
		StartUp.add({
			EuroSynth.addEventType;
		});
	}

	init {
		// create a random channel to exchange OSC messages
		// collision is possible but hopefully not occuring
		randSeed = 10000000.rand;

		tunerDefName = "EuroColiderTuner_%".format(randSeed).asSymbol;
		tunerOscChannel = "/EuroCollider/tuner/%".format(randSeed);

		tuningMap = ();
		isTuned = false;

		// if gateOut is nil we will create a bus to dump it to somewhere else
		// shall this get released on clear?
		gateOut = gateOut ? Bus.audio(Server.default, 1).index;

		this.startControlSynth;
		CmdPeriod.add({{this.startControlSynth}.defer(0.1)});

		if(playMonitor, {
			this.monitor.play;
		});
	}

	monitor {
		^Ndef("EuroColliderSynth_Monitor%".format(soundIn).asSymbol, {
			SoundIn.ar(soundIn)
		});
	}

	startControlSynth {
		controlSynth = SynthDef(\EuroColliderSynth, {|cvOut, gateOut, dcOffset=0, gate=0|
			var env = EnvGen.ar(
				envelope: Env.asr(attackTime: 0.001, releaseTime: 0.001),
				gate: gate,
			);
			Out.ar(cvOut, DC.ar(1.0)*dcOffset);
			Out.ar(gateOut, env);
		}).play(args: [
			\cvOut, cvOut,
			\gateOut, gateOut,
		]).register;
	}

	addTuner {
		SynthDef(tunerDefName, {|in, cvOut, dcOffset=0|
			var freq;
			var hasFreq;

			var soundIn = SoundIn.ar(in);
			#freq, hasFreq = Pitch.kr(soundIn, minFreq: 25, median: 10);
			SendReply.ar(
				trig: Pulse.ar(10.0),
				cmdName: tunerOscChannel,
				values: [freq, hasFreq],
			);
			Out.ar(cvOut, DC.ar(1.0) * dcOffset);
		}).add;
	}

	*addEventType {
		Event.addEventType(\euro, {
			var euroSynth = ~euro.value;
			if((euroSynth.class == EuroSynth).not, {
				"Add a tuned EuroSynth as \"euro\" parameter to you event".postln;
			});
			if(euroSynth.controlSynth.isPlaying.not, {
				"%: Control synth is not running".format(euroSynth).postln;
			}, {
				euroSynth.controlSynth.set(
					\dcOffset, euroSynth.freqCv(~freq.value);
				);
				euroSynth.controlSynth.set(
					\gate, 1.0,
				);
				// delay the gate down signal by sustain value
				{euroSynth.controlSynth.set(
					\gate, 0.0,
				)}.defer(~sustain.value);
			});
		});
	}

	tune { |baseFreq=55, steps=20, endRange=0.5, negativeSteps=0, minMatch=0.5|
		// prepare tuning procedure
		if((negativeSteps>0).and(negativeVoltage.not), {
			"%: Please spawn a EuroSynth which allows for negative voltages when tuning in negative domain".format(this).warn;
			^this;
		});

		if(tuner.notNil) {
			"%: Can only run one tuning process at a time".format(this).postln;
			^this;
		};

		oscFunc = OSCFunc({|msg|
			curFreq = msg[3];
		}, path: tunerOscChannel);

		tuner = SynthDef(tunerDefName, {|in, cvOut, dcOffset=0|
			var freq;
			var hasFreq;

			var soundIn = SoundIn.ar(in);
			#freq, hasFreq = Tartini.kr(soundIn);
			SendReply.ar(
				trig: Pulse.ar(10.0),
				cmdName: tunerOscChannel,
				values: [freq, hasFreq],
			);
			Out.ar(cvOut, DC.ar(1.0) * dcOffset);
		}).play(args: [
			\in, soundIn,
			\cvOut, cvOut,
		]);
		// start actual tuning async
		this.prTuningRoutine(baseFreq, steps, endRange, negativeSteps, minMatch);
	}

	prTuningRoutine { |baseFreq, steps, endRange, negativeSteps, minMatch|
		Routine({
			"%: Start tuning".format(this).postln;
			// tune within one quarter tone (default)
			// and from below
			while({(baseFreq.cpsmidi - (curFreq ? 10).cpsmidi).inRange(0.0, minMatch).not}, {
				// todo make this properly
				"%: Adjust tuning: % Hz".format(this, (curFreq?0) - baseFreq).postln;
				0.1.wait;
			});

			"%: Absolute pitch is tuned, start relative tuning".format(this).postln;

			// wait that hands get removed from tuning knob
			1.0.wait;

			((negativeSteps.neg..steps)/(steps*(endRange.reciprocal))).do({|i|
				tuner.set(\dcOffset, i);
				0.5.wait;
				tuningMap[i] = curFreq;
				"%: Step %:\t % Hz".format(this, i, curFreq).postln;
			});

			tuner.set(\dcOffset, 0.0);
			oscFunc.clear;
			tuner.free;
			tuner = nil;
			// check if we measured the same frequency twice which would indicate
			// a bad tuning procedure
			if((tuningMap.asArray.collect({|v| v.asInteger}).asSet.size < (steps+negativeSteps)), {
				"%: Tuning was probably unsuccessful as we measured the same freq at least twice. Try adjusting the tuning range or check wiring".format(this).postln;
				isTuned = false;
			}, {
				"%: Finished Tuning".format(this).postln;
				isTuned = true;
			});
		}).play;
	}

	resetTuning {
		tuningMap = ();
		isTuned = false;
	}

	freqCv {|freq|
		var tArray;
		var tFreqs;
		var tCvs;
		var fDistances;
		var closestIs;
		var omega;
		var cv;
		var lowestFreq;
		var highestFreq;

		if(isTuned.not, {
			"% is not tuned. Please tune it.".format(this).warn;
			^0.0;
		});

		// get 2 closest values in tuning array
		tArray = tuningMap.asSortedArray;
		tFreqs = tArray.collect({|i| i[1]});
		tCvs = tArray.collect({|i| i[0]});

		// get range of our tuning
		lowestFreq = tFreqs.sort[0];
		highestFreq = tFreqs.sort.reverse[0];

		// search for closest indices
		fDistances = tFreqs - freq;
		closestIs = (0..fDistances.size-1).sort({|a, b|
			fDistances.abs[a]<fDistances.abs[b];
		});


		// calculate w = (f-b)/(a-b)
		// where w is how much we need mix a and b to get f
		// which we will use to calculate the proper cv mix
		omega = (freq-tFreqs[closestIs[1]])/(tFreqs[closestIs[0]] - tFreqs[closestIs[1]]);
		if(freq.inRange(lowestFreq, highestFreq), {
			// now calculate cv = a*w + b(1-w)
			cv = (tCvs[closestIs[0]] * omega) + (tCvs[closestIs[1]] * (1-omega));
		}, {
			// in case we exceed the min/max we only use the boundary for calculation
			var freqToUse = if(freq <= lowestFreq, { lowestFreq }, { highestFreq });
			var cvReference = tCvs[tFreqs.indexOf(freqToUse)];
			cv = (freq/freqToUse)*cvReference;
		});

		if(negativeVoltage.not, {
			cv = cv.max(0.0);
		});
		^cv;
	}

	freq { |newFreq|
		this.controlSynth.set(
			\dcOffset, this.freqCv(newFreq)
		);
	}

	clear {
		controlSynth.free;
		this.resetTuning;
		this.monitor.clear;
	}

	printOn { | stream |
		stream << "EuroSynth(soundIn: " << soundIn << ", cvOut: " << cvOut << ")";
	}
}

EuroClockIn {
	var trigIn;
	var mean;
	var threshold;

	var oscChannel;
	var oscFunc;
	var controlSynth;
	var <timeStamps;
	var <clock;

	*new { |trigIn, mean=4, threshold=0.3|
		^super.newCopyArgs(
			trigIn,
			mean,
			threshold,
		).init;
	}

	init {
		clock = TempoClock();
		oscChannel = "/EuroCollider/EuroClockIn/%".format(trigIn);
		timeStamps = [];

		oscFunc = OSCFunc({|msg, time|
			this.prInsertTimeStamp(time);
			this.prUpdateClockTempo;
		}, oscChannel);

		controlSynth = SynthDef(\EuroClockIn, {|inBus, threshold|
			// use LocalIn to get a delay of one server buffer
			var soundIn = SoundIn.ar(inBus);
			var localIn = LocalIn.ar(1);
			var localOut = LocalOut.ar(DelayN.ar(soundIn, 0.05, 0.05));
			SendReply.ar(
				(SoundIn.ar(inBus)>=threshold) * (localIn<threshold),
				cmdName: oscChannel,
			);
			Silent.ar;
		}).play(args: [
			\inBus, trigIn,
			\threshold, threshold,
		]);
	}

	prInsertTimeStamp {|timeStamp|
		if(timeStamps.size >= mean, {
			timeStamps.removeAt(0);
		});
		timeStamps = timeStamps.add(timeStamp);
	}

	prCalculateTempo {
		var tempo = 1.0;
		if(timeStamps.size<2, {
			"Need at least 2 values before we can calculate a tempo".warn;
		}, {
			tempo = (0..timeStamps.size-2).collect({|i|
				timeStamps[i+1] - timeStamps[i];
			}).mean;
		});
		^tempo;
	}

	prUpdateClockTempo {
		if(timeStamps.size >= mean, {
			var curTempo =
			clock.setTempoAtSec(
				this.prCalculateTempo.reciprocal,
				timeStamps.reverse[0],
			);
		}, {
			"%: Received % out of % necessary beats".format(
				this,
				timeStamps.size,
				mean,
			).postln;
		});
	}

	printOn { | stream |
		stream << "EuroClockIn(trigIn: " << trigIn << ")";
	}

	clear {
		controlSynth.free;
		clock.stop;
	}
}

EuroClockOut {
	var <clock;
	var <trigOut;
	var <>resolution;
	var <controlSynth;
	var routine;

	*new { |clock, trigOut, resolution=1|
		^super.newCopyArgs(
			clock,
			trigOut,
			resolution,
		).init;
	}

	init {
		clock = clock ? TempoClock.default;
		controlSynth = SynthDef(\EuroColliderClockOut, {|trigOut, t_trig|
			var env = EnvGen.ar(Env.perc(0.001, 0.1), gate: t_trig);
			Out.ar(trigOut, env);
		}).play(args: [
			\trigOut, trigOut,
		]);

		routine = Routine({
			inf.do({
				if(clock.isRunning, {

					Server.default.makeBundle(
						time: Server.default.latency,
						func: {
							controlSynth.set(\t_trig, 1);
						},
					);
				});
				resolution.reciprocal.wait;
			});
		});
		clock.schedAbs(clock.elapsedBeats.roundUp(1), routine);
	}

	clear {
		routine.stop;
		controlSynth.free;
	}

	printOn { | stream |
		stream << "EuroClockOut(trigOut: " << trigOut << ")";
	}
}
