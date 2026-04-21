export interface BaseWsEvent {
  eventType: string;
  sessionId: string;
  gameId: string;
}

export interface MoveAppliedEvent extends BaseWsEvent {
  eventType: "MoveApplied";
  move: {
    from: string;
    to: string;
    promotion?: string;
  };
  playerWhoMoved: string;
}

export interface GameFinishedEvent extends BaseWsEvent {
  eventType: "GameFinished";
  status: string;
  winner?: string;
  drawReason?: string;
}

export interface SessionLifecycleChangedEvent extends BaseWsEvent {
  eventType: "SessionLifecycleChanged";
  from: string;
  to: string;
}

export interface SessionCreatedEvent extends BaseWsEvent {
  eventType: "SessionCreated";
  mode: string;
  whiteController: string;
  blackController: string;
}

export interface PromotionPendingEvent extends BaseWsEvent {
  eventType: "PromotionPending";
}

export interface AITurnRequestedEvent extends BaseWsEvent {
  eventType: "AITurnRequested";
  currentPlayer: string;
}

export interface AITurnCompletedEvent extends BaseWsEvent {
  eventType: "AITurnCompleted";
  move: {
    from: string;
    to: string;
    promotion?: string;
  };
}

export interface AITurnFailedEvent extends BaseWsEvent {
  eventType: "AITurnFailed";
  reason: string;
}

export interface MoveRejectedEvent extends BaseWsEvent {
  eventType: "MoveRejected";
  move: {
    from: string;
    to: string;
    promotion?: string;
  };
  reason: string;
}

export interface GameResignedEvent extends BaseWsEvent {
  eventType: "GameResigned";
  winner: string;
}

export interface SessionCancelledEvent extends BaseWsEvent {
  eventType: "SessionCancelled";
}

export type WsEvent =
  | MoveAppliedEvent
  | GameFinishedEvent
  | SessionLifecycleChangedEvent
  | SessionCreatedEvent
  | PromotionPendingEvent
  | AITurnRequestedEvent
  | AITurnCompletedEvent
  | AITurnFailedEvent
  | MoveRejectedEvent
  | GameResignedEvent
  | SessionCancelledEvent;
