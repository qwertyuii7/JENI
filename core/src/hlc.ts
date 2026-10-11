export class HybridLogicalClock {
  private lastWallTime: number = 0;
  private logical: number = 0;

  constructor(public readonly nodeId: string) {}

  public now(physicalTimeMs: number = Date.now()): string {
    if (physicalTimeMs > this.lastWallTime) {
      this.lastWallTime = physicalTimeMs;
      this.logical = 0;
    } else {
      this.logical++;
    }
    const iso = new Date(this.lastWallTime).toISOString();
    return `${iso}_${this.logical.toString().padStart(4, '0')}`;
  }

  public update(remoteHlc: string, physicalTimeMs: number = Date.now()): string {
    const [remoteIso, remoteCountStr] = remoteHlc.split('_');
    const remoteWall = new Date(remoteIso).getTime() || physicalTimeMs;
    const remoteCount = parseInt(remoteCountStr, 10) || 0;

    const maxWall = Math.max(physicalTimeMs, this.lastWallTime, remoteWall);
    if (maxWall === this.lastWallTime && maxWall === remoteWall) {
      this.logical = Math.max(this.logical, remoteCount) + 1;
    } else if (maxWall === remoteWall) {
      this.lastWallTime = remoteWall;
      this.logical = remoteCount + 1;
    } else {
      this.lastWallTime = maxWall;
      this.logical = 0;
    }
    const iso = new Date(this.lastWallTime).toISOString();
    return `${iso}_${this.logical.toString().padStart(4, '0')}`;
  }

  public static compare(
    a: { hlc: string; device_id: string },
    b: { hlc: string; device_id: string }
  ): number {
    if (a.hlc < b.hlc) return -1;
    if (a.hlc > b.hlc) return 1;
    if (a.device_id < b.device_id) return -1;
    if (a.device_id > b.device_id) return 1;
    return 0;
  }
}
