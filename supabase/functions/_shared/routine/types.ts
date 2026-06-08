export type RoutineDraft = {
  title: string;
  default_time: string;
  repeat_days: number[];
  note: string | null;
};

export type RoutineParseRequest = {
  text: string;
  timezone: string;
};

export type RoutineParseResponse = {
  routines: RoutineDraft[];
};
