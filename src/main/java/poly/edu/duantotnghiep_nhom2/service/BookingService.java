package poly.edu.duantotnghiep_nhom2.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import poly.edu.duantotnghiep_nhom2.entity.Booking;
import poly.edu.duantotnghiep_nhom2.entity.Invoice;
import poly.edu.duantotnghiep_nhom2.entity.Pitch;
import poly.edu.duantotnghiep_nhom2.entity.User;
import poly.edu.duantotnghiep_nhom2.entity.enums.BookingStatus;
import poly.edu.duantotnghiep_nhom2.entity.enums.PitchStatus;
import poly.edu.duantotnghiep_nhom2.repository.BookingRepository;
import poly.edu.duantotnghiep_nhom2.repository.InvoiceRepository;
import poly.edu.duantotnghiep_nhom2.repository.PitchRepository;
import poly.edu.duantotnghiep_nhom2.repository.UserRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class BookingService {

    private final BookingRepository bookingRepository;
    private final PitchRepository pitchRepository;
    private final UserRepository userRepository;
    private final InvoiceRepository invoiceRepository;

    private static final List<BookingStatus> EXCLUDED_STATUSES = Arrays.asList(BookingStatus.CANCELLED, BookingStatus.REFUNDED, BookingStatus.SWAPPED);

    public BookingService(BookingRepository bookingRepository, PitchRepository pitchRepository, UserRepository userRepository, InvoiceRepository invoiceRepository) {
        this.bookingRepository = bookingRepository;
        this.pitchRepository = pitchRepository;
        this.userRepository = userRepository;
        this.invoiceRepository = invoiceRepository;
    }

    public Booking getBookingById(Long id) {
        return bookingRepository.findById(id).orElseThrow(() -> new RuntimeException("Booking not found"));
    }

    public boolean hasActiveBooking(Long userId) {
        List<BookingStatus> activeStatuses = Arrays.asList(BookingStatus.PENDING, BookingStatus.CONFIRMED);
        return bookingRepository.existsByUserIdAndStatusIn(userId, activeStatuses);
    }

    @Transactional
    public Booking createBooking(Long userId, Long pitchId, LocalDateTime start, LocalDateTime end) {
        LocalDateTime now = LocalDateTime.now();

        // 1. Xử lý logic thời gian (Cho phép đặt trễ nếu còn > 60p)
        if (start.isBefore(now)) {
            if (end.isBefore(now)) {
                throw new RuntimeException("Khung giờ này đã kết thúc. Vui lòng chọn khung giờ khác.");
            }
            long remainingMinutes = Duration.between(now, end).toMinutes();
            if (remainingMinutes >= 60) {
                start = now;
            } else {
                throw new RuntimeException("Đã quá muộn để đặt khung giờ này (còn dưới 60 phút). Vui lòng chọn khung giờ tiếp theo.");
            }
        }

        if (hasActiveBooking(userId)) {
            throw new RuntimeException("Bạn đang có đơn đặt sân chưa hoàn thành.");
        }

        if (bookingRepository.existsByPitchIdAndOverlapTime(pitchId, start, end, EXCLUDED_STATUSES)) {
            throw new RuntimeException("Sân đã có người đặt trong khung giờ này!");
        }

        User user = userRepository.findById(userId).orElseThrow(() -> new RuntimeException("User not found"));
        Pitch pitch = pitchRepository.findById(pitchId).orElseThrow(() -> new RuntimeException("Pitch not found"));

        if (pitch.getStatus() == PitchStatus.MAINTENANCE) {
            throw new RuntimeException("Sân đang bảo trì.");
        }

        if (user.getLoyaltyPoints() >= 10 && 
            user.getLastPointUpdate() != null && 
            user.getLastPointUpdate().plusMonths(6).isBefore(now)) {
            user.setLoyaltyPoints(0);
            user.setLastPointUpdate(null);
            userRepository.save(user);
        }

        Booking booking = new Booking();
        booking.setUser(user);
        booking.setPitch(pitch);
        booking.setStartTime(start);
        booking.setEndTime(end);
        booking.setStatus(BookingStatus.PENDING);
        booking.setPaymentStatus("UNPAID");

        BigDecimal originalPrice = calculatePrice(pitch.getPricePerHour(), start, end);
        booking.setOriginalPrice(originalPrice);

        int points = user.getLoyaltyPoints();
        if (points > 0) {
            BigDecimal discountPercent = BigDecimal.valueOf(points).multiply(BigDecimal.valueOf(2.5));
            BigDecimal discountAmount = originalPrice.multiply(discountPercent).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
            booking.setDiscountAmount(discountAmount);
            booking.setTotalPrice(originalPrice.subtract(discountAmount));
            booking.setPointsUsed(points);
        } else {
            booking.setDiscountAmount(BigDecimal.ZERO);
            booking.setTotalPrice(originalPrice);
            booking.setPointsUsed(0);
        }

        if (user.getBalance().compareTo(booking.getTotalPrice()) < 0) {
            throw new RuntimeException("Số dư ví không đủ. Vui lòng nạp thêm tiền.");
        }
        user.setBalance(user.getBalance().subtract(booking.getTotalPrice()));
        userRepository.save(user);
        
        booking.setPaymentStatus("PAID");

        return bookingRepository.save(booking);
    }

    // Request Swap Pitch
    @Transactional
    public void requestSwapPitch(Long currentBookingId, Long newPitchId) {
        Booking current = bookingRepository.findById(currentBookingId).orElseThrow(() -> new RuntimeException("Booking not found"));
        Pitch newPitch = pitchRepository.findById(newPitchId).orElseThrow(() -> new RuntimeException("Pitch not found"));
        
        if (bookingRepository.existsByPitchIdAndOverlapTime(newPitchId, current.getStartTime(), current.getEndTime(), EXCLUDED_STATUSES)) {
            throw new RuntimeException("Sân mới bị trùng giờ!");
        }

        Booking newBooking = new Booking();
        newBooking.setUser(current.getUser());
        newBooking.setPitch(newPitch);
        newBooking.setStartTime(current.getStartTime());
        newBooking.setEndTime(current.getEndTime());
        newBooking.setStatus(BookingStatus.PENDING);
        newBooking.setPaymentStatus("UNPAID"); 
        
        BigDecimal newPrice = calculatePrice(newPitch.getPricePerHour(), current.getStartTime(), current.getEndTime());
        newBooking.setOriginalPrice(newPrice);
        newBooking.setTotalPrice(newPrice);
        
        newBooking.setNote("Yêu cầu đổi từ đơn #" + current.getId() + " (" + current.getPitch().getName() + ")");
        
        bookingRepository.save(newBooking);
    }

    // Approve Booking (Updated for Swap & Auto Check-in)
    @Transactional
    public void approveBooking(Long bookingId) {
        Booking booking = bookingRepository.findById(bookingId).orElseThrow(() -> new RuntimeException("Booking not found"));
        if (booking.getStatus() != BookingStatus.PENDING) {
            throw new RuntimeException("Chỉ có thể duyệt đơn đang chờ (PENDING).");
        }

        User user = booking.getUser();

        String note = booking.getNote();
        Long oldBookingId = null;
        if (note != null && note.startsWith("Yêu cầu đổi từ đơn #")) {
            Pattern pattern = Pattern.compile("#(\\d+)");
            Matcher matcher = pattern.matcher(note);
            if (matcher.find()) {
                oldBookingId = Long.parseLong(matcher.group(1));
            }
        }

        if (oldBookingId != null) {
            // --- XỬ LÝ ĐỔI SÂN (SAFE SWAP) ---
            Booking oldBooking = bookingRepository.findById(oldBookingId).orElse(null);
            if (oldBooking != null && oldBooking.getStatus() == BookingStatus.CONFIRMED) {
                
                // 1. Tính toán chênh lệch (Difference)
                BigDecimal oldPrice = oldBooking.getTotalPrice();
                BigDecimal newPrice = booking.getTotalPrice();
                BigDecimal difference = newPrice.subtract(oldPrice); // > 0: Trả thêm, < 0: Hoàn lại

                // 2. Kiểm tra ví (Chỉ cần đủ trả phần chênh lệch)
                if (user.getBalance().compareTo(difference) < 0) {
                    throw new RuntimeException("Số dư ví không đủ để thanh toán chênh lệch (" + difference + "). Vui lòng nạp thêm.");
                }

                // 3. Cập nhật ví (Trừ chênh lệch)
                user.setBalance(user.getBalance().subtract(difference));
                userRepository.save(user);

                // 4. Xử lý đơn cũ
                String oldTicketCode = oldBooking.getTicketCode();
                oldBooking.setTicketCode(oldTicketCode + "_SWAPPED_" + System.currentTimeMillis());
                oldBooking.setStatus(BookingStatus.SWAPPED);
                oldBooking.setNote(oldBooking.getNote() + " (Đã đổi sang đơn #" + booking.getId() + ")");
                if ("PAID".equals(oldBooking.getPaymentStatus())) {
                    oldBooking.setPaymentStatus("REFUNDED"); // Đánh dấu đã hoàn (thực tế là cấn trừ)
                }
                bookingRepository.save(oldBooking);
                bookingRepository.flush(); 
                
                // 5. Xử lý đơn mới
                booking.setPaymentStatus("PAID");
                booking.setTicketCode(oldTicketCode); // Dùng lại mã vé cũ
                booking.setNote("Đổi từ sân " + oldBooking.getPitch().getName() + " sang " + booking.getPitch().getName());
                booking.setCheckedIn(true); // Auto check-in
            }
        } else {
            // --- XỬ LÝ DUYỆT ĐƠN THƯỜNG ---
            if ("UNPAID".equals(booking.getPaymentStatus())) {
                 if (user.getBalance().compareTo(booking.getTotalPrice()) < 0) {
                    throw new RuntimeException("Số dư ví khách không đủ.");
                }
                user.setBalance(user.getBalance().subtract(booking.getTotalPrice()));
                booking.setPaymentStatus("PAID");
            }
        }

        userRepository.save(user);
        booking.setStatus(BookingStatus.CONFIRMED);
        bookingRepository.save(booking);
    }

    @Transactional
    public void cancelBooking(Long bookingId, Long userId) {
        Booking booking = bookingRepository.findById(bookingId).orElseThrow(() -> new RuntimeException("Booking not found"));
        if (LocalDateTime.now().plusHours(12).isAfter(booking.getStartTime())) {
            throw new RuntimeException("Chỉ được hủy sân trước giờ đá 12 tiếng.");
        }
        
        if ("PAID".equals(booking.getPaymentStatus())) {
            BigDecimal totalPaid = booking.getTotalPrice();
            BigDecimal refundAmount = totalPaid.multiply(new BigDecimal("0.70")).setScale(0, RoundingMode.HALF_UP);
            BigDecimal cancellationFee = totalPaid.subtract(refundAmount);

            User user = booking.getUser();
            user.setBalance(user.getBalance().add(refundAmount));
            userRepository.save(user);
            
            createCancellationInvoice(booking, cancellationFee);

            booking.setPaymentStatus("REFUNDED");
            booking.setNote("Hủy sân. Hoàn 70%: " + refundAmount + ". Phí 30%: " + cancellationFee);
        }

        booking.setStatus(BookingStatus.CANCELLED);
        bookingRepository.save(booking);
    }

    @Transactional
    public void rejectBooking(Long bookingId) {
        Booking booking = bookingRepository.findById(bookingId).orElseThrow(() -> new RuntimeException("Booking not found"));
        if (booking.getStatus() != BookingStatus.PENDING && booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new RuntimeException("Chỉ có thể hủy đơn đang chờ hoặc đã duyệt.");
        }
        
        if ("PAID".equals(booking.getPaymentStatus())) {
            BigDecimal totalPaid = booking.getTotalPrice();
            BigDecimal refundAmount = totalPaid.multiply(new BigDecimal("0.70")).setScale(0, RoundingMode.HALF_UP);
            BigDecimal cancellationFee = totalPaid.subtract(refundAmount);

            User user = booking.getUser();
            user.setBalance(user.getBalance().add(refundAmount));
            userRepository.save(user);
            
            createCancellationInvoice(booking, cancellationFee);

            booking.setPaymentStatus("REFUNDED");
            booking.setNote("Admin hủy. Hoàn 70%: " + refundAmount + ". Phí 30%: " + cancellationFee);
        }

        booking.setStatus(BookingStatus.CANCELLED);
        bookingRepository.save(booking);
    }

    @Transactional
    public Booking extendBooking(Long bookingId, int extraMinutes) {
        if (extraMinutes > 120) {
            throw new RuntimeException("Chỉ được gia hạn tối đa 120 phút.");
        }

        Booking currentBooking = bookingRepository.findById(bookingId).orElseThrow(() -> new RuntimeException("Booking not found"));
        
        // KIỂM TRA ĐÃ GIA HẠN CHƯA
        if (currentBooking.isExtended()) {
            throw new RuntimeException("Đơn hàng này đã được gia hạn một lần rồi. Không thể gia hạn thêm.");
        }

        LocalDateTime oldEnd = currentBooking.getEndTime();
        LocalDateTime newEnd = oldEnd.plusMinutes(extraMinutes);
        
        List<Booking> nextBookings = bookingRepository.findNextBookings(currentBooking.getPitch().getId(), oldEnd, EXCLUDED_STATUSES);
        
        if (!nextBookings.isEmpty()) {
            Booking next = nextBookings.get(0);
            if (newEnd.isAfter(next.getStartTime())) {
                throw new RuntimeException("Không thể gia hạn. Có khách đặt lúc " + next.getStartTime());
            }
        }
        
        BigDecimal extraPrice = calculatePrice(currentBooking.getPitch().getPricePerHour(), oldEnd, newEnd);
        
        User user = currentBooking.getUser();
        if (user.getBalance().compareTo(extraPrice) < 0) {
            throw new RuntimeException("Số dư ví không đủ để gia hạn.");
        }
        user.setBalance(user.getBalance().subtract(extraPrice));
        userRepository.save(user);
        
        currentBooking.setEndTime(newEnd);
        currentBooking.setOriginalPrice(currentBooking.getOriginalPrice().add(extraPrice));
        currentBooking.setTotalPrice(currentBooking.getTotalPrice().add(extraPrice));
        
        // ĐÁNH DẤU ĐÃ GIA HẠN
        currentBooking.setExtended(true);
        
        return bookingRepository.save(currentBooking);
    }

    // --- SỬA LẠI LOGIC SWAP PITCH (ADMIN DÙNG) - SAFE SWAP ---
    @Transactional
    public void swapPitch(Long currentBookingId, Long newPitchId) {
        Booking current = bookingRepository.findById(currentBookingId).orElseThrow(() -> new RuntimeException("Booking not found"));
        Pitch newPitch = pitchRepository.findById(newPitchId).orElseThrow(() -> new RuntimeException("Pitch not found"));
        LocalDateTime now = LocalDateTime.now();

        if (now.isBefore(current.getStartTime())) {
            if (bookingRepository.existsByPitchIdAndOverlapTime(newPitchId, current.getStartTime(), current.getEndTime(), EXCLUDED_STATUSES)) {
                throw new RuntimeException("Sân mới bị trùng giờ!");
            }
            
            User user = current.getUser();

            // 1. Tính toán giá mới
            BigDecimal newPrice = calculatePrice(newPitch.getPricePerHour(), current.getStartTime(), current.getEndTime());
            BigDecimal finalNewPrice = newPrice;
            
            // Áp dụng giảm giá (nếu có)
            if (current.getPointsUsed() != null && current.getPointsUsed() > 0) {
                BigDecimal discountPercent = BigDecimal.valueOf(current.getPointsUsed()).multiply(BigDecimal.valueOf(2.5));
                BigDecimal discountAmount = newPrice.multiply(discountPercent).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
                finalNewPrice = newPrice.subtract(discountAmount);
            }

            // 2. Tính chênh lệch (Difference)
            BigDecimal oldPrice = current.getTotalPrice();
            BigDecimal difference = finalNewPrice.subtract(oldPrice);

            // 3. Kiểm tra ví
            if (user.getBalance().compareTo(difference) < 0) {
                throw new RuntimeException("Số dư ví không đủ để thanh toán chênh lệch (" + difference + ").");
            }

            // 4. Cập nhật ví (Trừ chênh lệch)
            user.setBalance(user.getBalance().subtract(difference));
            userRepository.save(user);

            // 5. Xử lý đơn cũ
            String oldTicketCode = current.getTicketCode();
            current.setTicketCode(oldTicketCode + "_SWAPPED_" + System.currentTimeMillis());
            current.setStatus(BookingStatus.SWAPPED);
            current.setNote("Đã đổi sang sân " + newPitch.getName());
            if ("PAID".equals(current.getPaymentStatus())) {
                current.setPaymentStatus("REFUNDED");
            }
            bookingRepository.save(current);
            bookingRepository.flush();

            // 6. Tạo đơn mới
            Booking newBooking = new Booking();
            newBooking.setUser(user);
            newBooking.setPitch(newPitch);
            newBooking.setStartTime(current.getStartTime());
            newBooking.setEndTime(current.getEndTime());
            newBooking.setStatus(BookingStatus.CONFIRMED);
            newBooking.setPaymentStatus("PAID");
            
            newBooking.setOriginalPrice(newPrice);
            newBooking.setTotalPrice(finalNewPrice);
            
            if (current.getPointsUsed() != null) {
                newBooking.setPointsUsed(current.getPointsUsed());
                newBooking.setDiscountAmount(newPrice.subtract(finalNewPrice));
            } else {
                newBooking.setPointsUsed(0);
                newBooking.setDiscountAmount(BigDecimal.ZERO);
            }
            
            newBooking.setTicketCode(oldTicketCode);
            newBooking.setNote("Đổi từ sân " + current.getPitch().getName());
            newBooking.setCheckedIn(true); // Auto check-in

            bookingRepository.save(newBooking);
        }
        // ... (Logic đổi giữa giờ giữ nguyên hoặc cập nhật tương tự nếu cần) ...
    }

    @Transactional
    public void completeBooking(Long bookingId) {
        Booking booking = getBookingById(bookingId);
        if (booking.getStatus() == BookingStatus.SWAPPED) return;

        LocalDateTime now = LocalDateTime.now();

        if (now.isBefore(booking.getEndTime())) {
            BigDecimal actualCost = calculatePrice(booking.getPitch().getPricePerHour(), booking.getStartTime(), now);
            
            if (booking.getPointsUsed() != null && booking.getPointsUsed() > 0) {
                BigDecimal discountPercent = BigDecimal.valueOf(booking.getPointsUsed()).multiply(BigDecimal.valueOf(2.5));
                BigDecimal discountAmount = actualCost.multiply(discountPercent).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
                actualCost = actualCost.subtract(discountAmount);
                booking.setDiscountAmount(discountAmount);
            }
            
            BigDecimal amountPaid = booking.getTotalPrice();
            
            if (amountPaid.compareTo(actualCost) > 0) {
                BigDecimal refundAmount = amountPaid.subtract(actualCost);
                User user = booking.getUser();
                user.setBalance(user.getBalance().add(refundAmount));
                userRepository.save(user);
            }
            
            booking.setEndTime(now);
            booking.setTotalPrice(actualCost);
        }
        
        booking.setStatus(BookingStatus.COMPLETED);
        bookingRepository.save(booking);
        createInvoice(booking);

        User user = booking.getUser();
        long minutesPlayed = Duration.between(booking.getStartTime(), booking.getEndTime()).toMinutes();
        
        long currentAccumulated = user.getAccumulatedMinutes() + minutesPlayed;
        int pointsToAdd = (int) (currentAccumulated / 600); 
        long remainingMinutes = currentAccumulated % 600;

        if (pointsToAdd > 0) {
            int currentPoints = user.getLoyaltyPoints();
            int newPoints = Math.min(currentPoints + pointsToAdd, 10);
            user.setLoyaltyPoints(newPoints);
            user.setAccumulatedMinutes(remainingMinutes);
            
            if (newPoints >= 10) {
                user.setLastPointUpdate(LocalDateTime.now());
            }
        } else {
            user.setAccumulatedMinutes(currentAccumulated);
        }
        userRepository.save(user);
    }

    private void createInvoice(Booking booking) {
        if (invoiceRepository.findByBookingId(booking.getId()).isPresent()) {
            return;
        }
        Invoice invoice = new Invoice();
        invoice.setBooking(booking);
        invoice.setAmount(booking.getTotalPrice());
        String method = "UNPAID".equals(booking.getPaymentStatus()) ? "CASH" : "VNPAY/WALLET";
        invoice.setPaymentMethod(method);
        invoice.setTransactionCode("INV-" + System.currentTimeMillis());
        invoiceRepository.save(invoice);
    }

    private void createCancellationInvoice(Booking booking, BigDecimal feeAmount) {
        Invoice invoice = new Invoice();
        invoice.setBooking(booking);
        invoice.setAmount(feeAmount);
        invoice.setPaymentMethod("WALLET_DEDUCT");
        invoice.setTransactionCode("CANCEL_FEE-" + System.currentTimeMillis());
        invoiceRepository.save(invoice);
    }

    private BigDecimal calculatePrice(BigDecimal pricePerHour, LocalDateTime start, LocalDateTime end) {
        long minutes = Duration.between(start, end).toMinutes();
        if (minutes < 0) minutes = 0;
        return pricePerHour.multiply(BigDecimal.valueOf(minutes))
                .divide(BigDecimal.valueOf(60), 0, RoundingMode.HALF_UP); // Làm tròn về số nguyên
    }

    public List<Booking> getHistoryByUser(Long userId) {
        List<Booking> bookings = bookingRepository.findByUserIdOrderByStartTimeDesc(userId);
        return bookings.stream()
                .sorted(Comparator.comparingInt((Booking b) -> {
                    switch (b.getStatus()) {
                        case CONFIRMED: return 1;
                        case PENDING: return 2;
                        case COMPLETED: return 3;
                        case SWAPPED: return 4;
                        case CANCELLED: return 5;
                        case REFUNDED: return 6;
                        default: return 7;
                    }
                }))
                .collect(Collectors.toList());
    }

    public List<Booking> getPendingBookings() {
        return bookingRepository.findByStatusOrderByCreatedAtDesc(BookingStatus.PENDING);
    }

    public List<Booking> findBookingsByDateRange(LocalDateTime start, LocalDateTime end) {
        return bookingRepository.findBookingsByDateRange(start, end);
    }

    public Booking checkInTicket(String ticketCode) {
        Booking booking = bookingRepository.findByTicketCode(ticketCode)
                .orElseThrow(() -> new RuntimeException("Mã vé không tồn tại!"));

        if (booking.isCheckedIn()) {
            throw new RuntimeException("Vé này đã được sử dụng (Check-in) rồi!");
        }

        if (booking.getStatus() != BookingStatus.CONFIRMED && booking.getStatus() != BookingStatus.PENDING) {
            throw new RuntimeException("Trạng thái vé không hợp lệ (Đã hủy hoặc hoàn thành).");
        }

        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(booking.getStartTime().minusMinutes(30))) {
            throw new RuntimeException("Chưa đến giờ đá. Vui lòng quay lại sau.");
        }
        if (now.isAfter(booking.getEndTime().plusMinutes(30))) {
            throw new RuntimeException("Vé đã hết hạn sử dụng.");
        }

        booking.setCheckedIn(true);
        if (booking.getStatus() == BookingStatus.PENDING) {
            booking.setStatus(BookingStatus.CONFIRMED);
        }
        
        return bookingRepository.save(booking);
    }
}
