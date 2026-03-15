package poly.edu.duantotnghiep_nhom2.controller;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import poly.edu.duantotnghiep_nhom2.entity.Booking;
import poly.edu.duantotnghiep_nhom2.entity.Pitch;
import poly.edu.duantotnghiep_nhom2.entity.User;
import poly.edu.duantotnghiep_nhom2.service.BookingService;
import poly.edu.duantotnghiep_nhom2.service.PitchService;
import poly.edu.duantotnghiep_nhom2.service.UserService;

import java.security.Principal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Controller
@RequestMapping("/booking")
public class BookingController {

    private final BookingService bookingService;
    private final UserService userService;
    private final PitchService pitchService;

    public BookingController(BookingService bookingService, UserService userService, PitchService pitchService) {
        this.bookingService = bookingService;
        this.userService = userService;
        this.pitchService = pitchService;
    }

    @PostMapping("/create")
    public String createBooking(
            @RequestParam Long pitchId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date,
            @RequestParam @DateTimeFormat(pattern = "HH:mm") LocalTime startTime,
            @RequestParam Integer duration,
            Principal principal,
            RedirectAttributes redirectAttributes) {

        // Nếu chưa đăng nhập
        if (principal == null) {
            return "redirect:/login";
        }

        try {

            String username = principal.getName();
            User user = userService.findByUsername(username)
                    .orElseThrow(() -> new RuntimeException("Không tìm thấy người dùng"));

            LocalDateTime startDateTime = LocalDateTime.of(date, startTime);

        /* =============================
           XÁC ĐỊNH KHUNG GIỜ CỦA SLOT
        ============================== */
            LocalDateTime slotEndTime;

            switch (startTime.getHour()) {
                case 7:
                    slotEndTime = LocalDateTime.of(date, LocalTime.of(9, 0));
                    break;
                case 10:
                    slotEndTime = LocalDateTime.of(date, LocalTime.of(12, 0));
                    break;
                case 14:
                    slotEndTime = LocalDateTime.of(date, LocalTime.of(16, 0));
                    break;
                case 18:
                    slotEndTime = LocalDateTime.of(date, LocalTime.of(20, 0));
                    break;
                default:
                    slotEndTime = startDateTime.plusMinutes(duration);
            }

        /* =============================
           TÍNH GIỜ KẾT THÚC
        ============================== */
            LocalDateTime endDateTime = startDateTime.plusMinutes(duration);

            if (endDateTime.isAfter(slotEndTime)) {
                endDateTime = slotEndTime;
            }

        /* =============================
           TẠO BOOKING
        ============================== */
            bookingService.createBooking(
                    user.getId(),
                    pitchId,
                    startDateTime,
                    endDateTime
            );

            redirectAttributes.addFlashAttribute(
                    "success",
                    "✅ Đặt sân thành công! Vui lòng chờ admin xác nhận."
            );

        } catch (Exception e) {

            redirectAttributes.addFlashAttribute(
                    "error",
                    "❌ Không thể đặt sân: " + (e.getMessage() != null ? e.getMessage() : "Lỗi hệ thống")
            );

            return "redirect:/pitches/" + pitchId;
        }

        return "redirect:/profile";
    }

    @PostMapping("/confirm")
    public String confirmBooking(
            @RequestParam Long pitchId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate date,
            @RequestParam @DateTimeFormat(pattern = "HH:mm") LocalTime startTime,
            @RequestParam Integer duration,
            Model model) {

        Pitch pitch = pitchService.getPitchById(pitchId);

        LocalDateTime start = LocalDateTime.of(date, startTime);
        LocalDateTime end = start.plusMinutes(duration);

        model.addAttribute("pitch", pitch);
        model.addAttribute("date", date);
        model.addAttribute("startTime", startTime);
        model.addAttribute("endTime", end.toLocalTime());
        model.addAttribute("duration", duration);

        return "booking-confirm";
    }
    @GetMapping("/cancel/{id}")
    public String cancelBooking(@PathVariable Long id, Principal principal, RedirectAttributes redirectAttributes) {
        if (principal == null) return "redirect:/login";
        try {
            User user = userService.findByUsername(principal.getName()).orElseThrow();
            bookingService.cancelBooking(id, user.getId());
            redirectAttributes.addFlashAttribute("success", "Đã hủy đặt sân thành công.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/profile";
    }

    @PostMapping("/extend")
    public String extendBooking(@RequestParam Long bookingId, @RequestParam int extraMinutes, RedirectAttributes redirectAttributes) {
        try {
            bookingService.extendBooking(bookingId, extraMinutes);
            redirectAttributes.addFlashAttribute("success", "Gia hạn thành công!");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/profile";
    }

    // Trang chọn sân để đổi (Khách hàng)
    @GetMapping("/swap-options/{id}")
    public String swapOptions(@PathVariable Long id, Model model, Principal principal, RedirectAttributes redirectAttributes) {
        if (principal == null) return "redirect:/login";
        try {
            Booking currentBooking = bookingService.getBookingById(id);
            
            // Tìm các sân trống cùng khung giờ (Bỏ qua facilityId)
            List<Pitch> availablePitches = pitchService.findAvailablePitches(
                    null, // facilityId = null
                    currentBooking.getPitch().getType(),
                    currentBooking.getStartTime(),
                    currentBooking.getEndTime()
            );
            // Loại bỏ sân hiện tại
            availablePitches.removeIf(p -> p.getId().equals(currentBooking.getPitch().getId()));
            
            model.addAttribute("currentBooking", currentBooking);
            model.addAttribute("availablePitches", availablePitches);
            return "swap-options"; // Tạo file template mới cho khách
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/profile";
        }
    }

    // Xử lý yêu cầu đổi sân (Khách hàng)
    @PostMapping("/swap")
    public String requestSwap(@RequestParam Long bookingId, @RequestParam Long newPitchId, RedirectAttributes redirectAttributes) {
        try {
            bookingService.requestSwapPitch(bookingId, newPitchId);
            redirectAttributes.addFlashAttribute("success", "Đã gửi yêu cầu đổi sân. Vui lòng chờ Admin duyệt.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Lỗi: " + e.getMessage());
        }
        return "redirect:/profile";
    }
}
