import { NextFunction, Request, RequestHandler, Response } from "express";

export const asyncHandler =
    (fn: (req: Request, res: Response, next: NextFunction) => Promise<void>): RequestHandler =>
    (req, res, next) => {
        fn(req, res, next).catch((err: unknown) => {
            // Express treats next(null) as a normal continuation, which turns a
            // rejected null value into a misleading 404 instead of an error.
            next(err ?? new Error("Async handler rejected without an error value"));
        });
    };
