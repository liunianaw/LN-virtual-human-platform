package com.ruoyi.session.runtime;

import org.springframework.http.HttpStatus;

/** Stable, non-sensitive errors for the small runtime HTTP boundary. */
public final class RuntimeProblem extends RuntimeException
{
    private final HttpStatus status;
    private final String code;

    public RuntimeProblem(HttpStatus status, String code, String message)
    {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status()
    {
        return status;
    }

    public String code()
    {
        return code;
    }
}
