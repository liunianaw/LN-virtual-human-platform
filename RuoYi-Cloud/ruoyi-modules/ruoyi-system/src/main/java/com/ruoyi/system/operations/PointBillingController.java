package com.ruoyi.system.operations;

import java.math.BigDecimal;
import java.util.Map;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.operations.dto.PointRatePublishRequest;
import com.ruoyi.system.operations.service.PointBillingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
public class PointBillingController
{
    private final PointBillingService billing;
    public PointBillingController(PointBillingService billing){this.billing=billing;}

    @RequiresPermissions("platform:quota:manage")
    @GetMapping("/point-rates")
    public AjaxResult rates(){administrator();return AjaxResult.success(billing.publishedRates());}

    @RequiresPermissions("platform:quota:manage")
    @Log(title="积分费率发布",businessType=BusinessType.INSERT,isSaveRequestData=false)
    @PostMapping("/point-rates")
    public AjaxResult publish(@Valid @RequestBody PointRatePublishRequest input)
    {administrator();return AjaxResult.success(billing.publish(SecurityUtils.getUserId(),input));}

    @RequiresPermissions("platform:quota:manage")
    @Log(title="账号积分授予",businessType=BusinessType.INSERT,isSaveRequestData=false)
    @PostMapping("/accounts/{accountId}/point-grants")
    public AjaxResult grant(@PathVariable long accountId,@Valid @RequestBody Grant input,
        @RequestHeader(value="Idempotency-Key",required=false) String key)
    {
        administrator();
        boolean applied=billing.grant(SecurityUtils.getUserId(),accountId,input.points(),key,input.reason().trim());
        return AjaxResult.success(Map.of("applied",applied));
    }

    @RequiresPermissions("platform:operations:reconcile")
    @Log(title="积分预占核对",businessType=BusinessType.UPDATE,isSaveRequestData=false)
    @PostMapping("/point-reservations/{reservationId}/reviews")
    public AjaxResult review(@PathVariable long reservationId,@Valid @RequestBody Review input)
    {
        administrator();
        if(!java.util.Set.of("SETTLE","RELEASE").contains(input.decision())) throw new ServiceException("积分核对决定无效",400);
        if(!billing.review(SecurityUtils.getUserId(),reservationId,input.decision(),input.evidenceNote().trim()))
            throw new ServiceException("积分预占不存在",404);
        return AjaxResult.success();
    }

    private static void administrator(){if(!SecurityUtils.isAdmin())throw new ServiceException("仅管理员可管理积分",403);}
    public record Grant(@NotNull @DecimalMin("0.01") @Digits(integer=12,fraction=2) BigDecimal points,
        @NotBlank @Size(max=500) String reason){}
    public record Review(@NotBlank String decision,@NotBlank @Size(max=500) String evidenceNote){}
}
