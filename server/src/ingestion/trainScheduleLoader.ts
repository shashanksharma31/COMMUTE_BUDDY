import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { z } from 'zod';

const railScheduleItemSchema = z.object({
  trainNumber: z.string().min(3),
  trainName: z.string().min(1),
  fromStationId: z.string().min(1),
  toStationId: z.string().min(1),
  departureSeconds: z.number().int().nonnegative(),
  arrivalSeconds: z.number().int().nonnegative(),
  travelDurationSeconds: z.number().int().positive(),
  runningDays: z.array(z.enum(['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'])).min(1),
  operator_deeplink: z.string().url(),
  source_url: z.string().url(),
  effective_from: z.string().min(10)
});

const railScheduleFileSchema = z.object({
  meta: z.object({
    description: z.string(),
    source_url: z.string().url(),
    effective_from: z.string().min(10)
  }),
  schedules: z.array(railScheduleItemSchema)
});

export type RailStaticScheduleEntry = z.infer<typeof railScheduleItemSchema>;

export class StaticTrainScheduleStore {
  private readonly schedules: RailStaticScheduleEntry[] = [];

  constructor(customPath?: string) {
    this.loadFromDisk(customPath);
  }

  private loadFromDisk(customPath?: string): void {
    const currentDir = path.dirname(fileURLToPath(import.meta.url));
    const candidates = customPath
      ? [customPath]
      : [
          path.resolve(currentDir, '../../data/rail/ir_static_timetable.json'),
          path.resolve(process.cwd(), 'data/rail/ir_static_timetable.json'),
          path.resolve(process.cwd(), 'server/data/rail/ir_static_timetable.json')
        ];

    for (const p of candidates) {
      if (fs.existsSync(p)) {
        const parsed = railScheduleFileSchema.parse(
          JSON.parse(fs.readFileSync(p, 'utf8'))
        );
        this.schedules.push(...parsed.schedules);
        return;
      }
    }
    throw new Error('Could not locate server/data/rail/ir_static_timetable.json');
  }

  findSchedulesBetween(
    fromStationId: string,
    toStationId: string
  ): RailStaticScheduleEntry[] {
    return this.schedules.filter(
      (s) => s.fromStationId === fromStationId && s.toStationId === toStationId
    );
  }

  getByTrainNumber(trainNumber: string): RailStaticScheduleEntry | null {
    return this.schedules.find((s) => s.trainNumber === trainNumber) ?? null;
  }

  getAllSchedules(): ReadonlyArray<RailStaticScheduleEntry> {
    return this.schedules;
  }
}

export const staticTrainScheduleStore = new StaticTrainScheduleStore();
