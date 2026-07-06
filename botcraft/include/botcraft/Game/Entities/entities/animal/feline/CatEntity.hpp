#if PROTOCOL_VERSION > 404 /* > 1.13.2 */
#pragma once

#include "botcraft/Game/Entities/entities/TamableAnimalEntity.hpp"

namespace Botcraft
{
    class CatEntity : public TamableAnimalEntity
    {
    protected:
#if PROTOCOL_VERSION < 775 /* < 26.1 */
        static constexpr int metadata_count = 4;
#else
        static constexpr int metadata_count = 5;
#endif
        static const std::array<std::string, metadata_count> metadata_names;
        static constexpr int hierarchy_metadata_count = TamableAnimalEntity::metadata_count + TamableAnimalEntity::hierarchy_metadata_count;

    public:
        CatEntity();
        virtual ~CatEntity();

        // Object related stuff
        virtual std::string GetName() const override;
        virtual EntityType GetType() const override;

        // Static stuff, for easier comparison
        static std::string GetClassName();
        static EntityType GetClassType();


        virtual ProtocolCraft::Json::Value Serialize() const override;

        // Metadata stuff
        virtual void SetMetadataValue(const int index, const std::any& value) override;

        int GetDataTypeId() const;
        bool GetIsLying() const;
        bool GetRelaxStateOne() const;
        int GetDataCollarColor() const;
#if PROTOCOL_VERSION > 774 /* > 1.21.11 */
        int GetDataSoundVariantId() const;
#endif

        void SetDataTypeId(const int data_type_id);
        void SetIsLying(const bool is_lying);
        void SetRelaxStateOne(const bool relax_state_one);
        void SetDataCollarColor(const int data_collar_color);
#if PROTOCOL_VERSION > 774 /* > 1.21.11 */
        void SetDataSoundVariantId(const int data_sound_variant_id);
#endif

        // Attribute stuff
        double GetAttributeAttackDamageValue() const;

    protected:
        virtual double GetWidthImpl() const override;
        virtual double GetHeightImpl() const override;

    };
}
#endif
