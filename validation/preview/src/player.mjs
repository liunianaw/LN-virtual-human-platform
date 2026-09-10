export class Player {
  constructor() {
    this.current = 'idle';
    this.queue = [];
    this.speech = false;
    this.epoch = 0;
    this.revision = 0;
  }
  select(action) { this.current = action; this.revision++; }
  beginAudio() { this.stop(); return this.epoch; }
  action(name) {
    if (this.speech) {
      if (this.queue.length < 8) this.queue.push(name);
    } else this.select(name);
  }
  audio(event, epoch = this.epoch) {
    if (epoch !== this.epoch) return;
    if (event === 'playing') { this.speech = true; this.select('speaking'); }
    if (event === 'pause' || event === 'waiting') this.select('idle');
    if (event === 'ended' || event === 'error') {
      this.speech = false;
      this.select(this.queue.shift() || 'idle');
    }
  }
  complete() {
    if (!this.speech) this.select(this.queue.shift() || 'idle');
  }
  stop() {
    this.epoch++;
    this.queue = [];
    this.speech = false;
    this.select('idle');
  }
}
