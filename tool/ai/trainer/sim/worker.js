/*
 * Worker の中身。試合を1つ頼まれたら最後まで回して結果を返すだけ。
 *
 * ページが core・terrain・brain・tactics・match とこの関数の文字列を束ねて Blob の Worker にする
 * （trainer.js の makeWorker）。ページで呼ばれても何もしない。
 */
function AVSim_worker(AV) {
  "use strict";

  if (typeof WorkerGlobalScope === "undefined" || !(self instanceof WorkerGlobalScope)) {
    return;
  }

  // 読み込んだワールドの会場。試合ごとに送らず、1度受け取って名前で引く。
  const worlds = new Map();

  self.onmessage = (event) => {
    const job = event.data;

    if (job.world) {
      worlds.clear();
      worlds.set(job.world.key, job.world.data);
      return;
    }

    const started = performance.now();

    try {
      const options = job.options;

      if (options.map && options.map.worldKey) {
        const world = worlds.get(options.map.worldKey);

        if (!world) throw new Error("world " + options.map.worldKey + " was not sent to this worker");

        options.map = { world };
      }

      const match = new AV.Match(options);
      const result = match.run();

      self.postMessage({ jobId: job.jobId, result, ms: performance.now() - started });
    } catch (error) {
      self.postMessage({ jobId: job.jobId, error: String(error && error.stack ? error.stack : error) });
    }
  };
}
