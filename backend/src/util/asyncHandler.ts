import { NextFunction, Request, RequestHandler, Response } from "express";

export const asyncHandler =
    (fn: (req: Request, res: Response, next: NextFunction) => Promise<void>): RequestHandler =>
    (req, res, next) => {
        fn(req, res, next).catch((err: unknown) => {
            next(err);
        });
    };